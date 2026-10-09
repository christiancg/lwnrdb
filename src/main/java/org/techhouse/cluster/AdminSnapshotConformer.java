package org.techhouse.cluster;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.techhouse.bckg_ops.ScheduleRegistry;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.ScheduleDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.MetadataReadException;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;
import org.techhouse.ops.admin.LeftoverFolders;

// The admin epoch is the ordering, so there is no per-record version, and every step is
// idempotent so the periodic sweep does not rewrite an already-converged node.
final class AdminSnapshotConformer {
    private final Logger logger = Logger.logFor(AdminSnapshotConformer.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final HybridClock hybridClock = IocContainer.get(HybridClock.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final org.techhouse.ops.CompiledProcedureCache compiledProcedures = IocContainer
            .get(org.techhouse.ops.CompiledProcedureCache.class);
    private final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);
    private final AdminQuarantine quarantine = new AdminQuarantine();

    boolean conform(AdminSnapshotPayload snapshot, long epochAtStart) throws Exception {
        final var snapshotUsers = conformUsers(snapshot);
        removeAbsentUsers(snapshotUsers);
        final var outcome = new ConformOutcome();
        final var unreadable = new UnreadableItems(Set.copyOf(snapshot.getUnreadable()), snapshot.getNodeId());
        final var snapshotDbs = conformDatabases(snapshot, outcome);
        outcome.record(conformProcedures(snapshot, snapshotDbs, unreadable));
        outcome.record(conformSchedules(snapshot, snapshotDbs, unreadable));
        final var snapshotColls = conformCollections(snapshot, snapshotDbs, epochAtStart, outcome, unreadable);
        if (adminEpoch.current() != epochAtStart) {
            logger.warning("Skipping the quarantine phase of the admin conform: a local admin op committed"
                    + " during it. The next round reconciles from the newer local state.");
            return false;
        }
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        outcome.record(quarantine.dropAbsentCollections(snapshotDbs, snapshotColls, waitMillis));
        outcome.record(quarantine.dropAbsentDatabases(snapshotDbs, waitMillis));
        return outcome.complete;
    }

    private static final class ConformOutcome {
        private boolean complete = true;

        void record(boolean stepComplete) {
            complete &= stepComplete;
        }
    }

    private record UnreadableItems(Set<String> keys, String nodeId) {
        boolean contains(String key) {
            return keys.contains(key);
        }
    }

    private boolean skipUnreadable(String key, UnreadableItems unreadable) {
        logger.warning("Leaving " + key + " as it is: the admin snapshot of " + unreadable.nodeId()
                + " could not read it. The next round retries it.");
        return false;
    }

    private record Removal(boolean complete, boolean removedAny) {
    }

    private interface DefinitionRemover {
        void remove(String name) throws IOException;
    }

    private Removal removeAbsentDefinitions(String dbName, String folderKey, NameListing listing,
            BiFunction<String, String, String> itemKey, Map<String, JsonObject> desired, UnreadableItems unreadable,
            DefinitionRemover remover) {
        if (unreadable.contains(folderKey)) {
            return new Removal(skipUnreadable(folderKey, unreadable), false);
        }
        final List<String> existing;
        try {
            existing = listing.names();
        } catch (IOException e) {
            logger.warning("Removing nothing from " + folderKey + ": it could not be listed. The next round retries"
                    + " it: " + e.getMessage());
            return new Removal(false, false);
        }
        var complete = true;
        var removedAny = false;
        for (final var name : existing) {
            final var key = itemKey.apply(dbName, name);
            if (unreadable.contains(key)) {
                complete &= skipUnreadable(key, unreadable);
            } else if (!desired.containsKey(Cache.getCollectionIdentifier(dbName, name))) {
                try {
                    remover.remove(name);
                    removedAny = true;
                } catch (IOException e) {
                    logger.warning("Could not remove " + key + ". The next round retries it: " + e.getMessage());
                    complete = false;
                }
            }
        }
        return new Removal(complete, removedAny);
    }

    private HashSet<String> conformUsers(AdminSnapshotPayload snapshot) throws Exception {
        final var snapshotUsers = new HashSet<String>();
        for (final var userJson : snapshot.getUsers()) {
            final var user = AdminUserEntry.fromJsonObject(userJson);
            snapshotUsers.add(user.get_id());
            final var local = cache.getAdminUserEntry(user.get_id());
            if (local == null || !local.getData().equals(user.getData())) {
                AdminOperationHelper.saveUserEntry(user);
            }
        }
        return snapshotUsers;
    }

    private void removeAbsentUsers(HashSet<String> snapshotUsers) throws Exception {
        for (final var localUser : new ArrayList<>(cache.getAllAdminUserEntries())) {
            if (!snapshotUsers.contains(localUser.get_id())) {
                AdminOperationHelper.deleteUserEntry(localUser.get_id());
            }
        }
    }

    private HashMap<String, AdminDbEntry> conformDatabases(AdminSnapshotPayload snapshot, ConformOutcome outcome)
            throws Exception {
        final var snapshotDbs = new HashMap<String, AdminDbEntry>();
        for (final var dbJson : snapshot.getDatabases()) {
            final var db = AdminDbEntry.fromJsonObject(dbJson);
            snapshotDbs.put(db.get_id(), db);
        }
        for (final var db : new ArrayList<>(snapshotDbs.values())) {
            if (!clearedOfCaseVariant(db.get_id(), snapshotDbs.keySet()) || !clearedOfLeftovers(db.get_id())) {
                snapshotDbs.remove(db.get_id());
                outcome.record(false);
                continue;
            }
            // Outside the entry check on purpose, and idempotent: the folder is what a routed write opens, so
            // a node holding the admin entry without it answers "no such file or directory" instead of a miss.
            fs.createDatabaseFolder(db.get_id());
            if (cache.getAdminDbEntry(db.get_id()) == null) {
                AdminOperationHelper.saveDatabaseEntry(
                        new AdminDbEntry(db.get_id(), new ArrayList<>(), new ArrayList<>(db.getOwners())));
            } else if (!cache.getAdminDbEntry(db.get_id()).getOwners().equals(db.getOwners())) {
                AdminOperationHelper.updateDatabaseOwners(db.get_id(), db.getOwners());
            }
        }
        return snapshotDbs;
    }

    private boolean clearedOfCaseVariant(String dbName, Set<String> snapshotDbNames) throws Exception {
        return cache.getAdminDbEntry(dbName) != null || quarantine.clearCaseVariantDatabase(dbName, snapshotDbNames,
                clusterConfig.replicationAckTimeoutMs());
    }

    private boolean clearedOfCaseVariant(String dbName, String collName, Set<String> snapshotCollIds) throws Exception {
        return cache.getAdminCollectionEntry(dbName, collName) != null || quarantine.clearCaseVariantCollection(dbName,
                collName, snapshotCollIds, clusterConfig.replicationAckTimeoutMs());
    }

    private boolean clearedOfLeftovers(String dbName) throws InterruptedException {
        if (cache.getAdminDbEntry(dbName) != null) {
            return true;
        }
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockDatabaseExclusive(dbName, waitMillis)) {
            logger.warning("Skipping the install of database " + dbName + ": it stayed locked for " + waitMillis
                    + "ms. The next round retries it.");
            return false;
        }
        try {
            return LeftoverFolders.moveAsideUnregisteredDatabase(dbName);
        } finally {
            locks.releaseDatabaseExclusive(dbName);
        }
    }

    private HashSet<String> conformCollections(AdminSnapshotPayload snapshot, HashMap<String, AdminDbEntry> snapshotDbs,
            long epochAtStart, ConformOutcome outcome, UnreadableItems unreadable) {
        final var snapshotColls = new HashSet<String>();
        final var snapshotCollIds = new HashSet<String>();
        final var collections = snapshot.getCollections().stream().map(AdminCollEntry::fromJsonObject).toList();
        collections.forEach(coll -> snapshotCollIds.add(coll.get_id()));
        for (final var coll : collections) {
            final var parts = coll.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            final var dbName = parts[0];
            final var collName = parts[1];
            if (!snapshotDbs.containsKey(dbName)) {
                continue;
            }
            snapshotColls.add(coll.get_id());
            final var schemaEl = snapshot.getSchemas().get(coll.get_id());
            final var desiredSchema = schemaEl != null && schemaEl.isJsonObject() ? schemaEl.asJsonObject() : null;
            try {
                if (!clearedOfCaseVariant(dbName, collName, snapshotCollIds)) {
                    outcome.record(false);
                    continue;
                }
                outcome.record(conformCollection(dbName, collName, coll.getIndexes(), desiredSchema,
                        snapshot.getTriggers(), epochAtStart, coll.getIncarnation(), unreadable));
            } catch (Exception e) {
                outcome.record(false);
                logger.warning("Skipping the admin conform of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                        + " for this round: " + e.getMessage());
            }
        }
        return snapshotColls;
    }

    private boolean conformProcedures(AdminSnapshotPayload snapshot, HashMap<String, AdminDbEntry> snapshotDbs,
            UnreadableItems unreadable) throws Exception {
        var complete = true;
        final var desired = new HashMap<String, JsonObject>();
        for (final var entry : snapshot.getProcedures().entrySet()) {
            desired.put(entry.getKey(), entry.getValue().asJsonObject());
        }
        for (final var dbName : snapshotDbs.keySet()) {
            final var waitMillis = clusterConfig.replicationAckTimeoutMs();
            if (!locks.tryLockWrite(dbName, Globals.PROCEDURES_FOLDER, waitMillis)) {
                logger.warning("Skipping the procedures conform of " + dbName + ": its lock stayed held" + " for "
                        + waitMillis + "ms. The next round retries it.");
                complete = false;
                continue;
            }
            try {
                complete &= removeAbsentDefinitions(dbName, AdminSnapshotKeys.procedures(dbName),
                        () -> fs.listProcedureNames(dbName), AdminSnapshotKeys::procedure, desired, unreadable,
                        name -> {
                            fs.deleteProcedure(dbName, name);
                            cache.removeProcedure(dbName, name);
                            compiledProcedures.invalidateProcedure(dbName, name);
                        }).complete();
                for (final var entry : desired.entrySet()) {
                    final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
                    if (parts.length < 2 || !parts[0].equals(dbName)) {
                        continue;
                    }
                    final var definition = ProcedureDefinition.fromJsonObject(entry.getValue());
                    if (!definition.equals(localProcedure(dbName, parts[1]))) {
                        fs.writeProcedure(dbName, parts[1], eJson.toJson(entry.getValue()));
                        cache.removeProcedure(dbName, parts[1]);
                        compiledProcedures.invalidateProcedure(dbName, parts[1]);
                    }
                }
            } finally {
                locks.release(dbName, Globals.PROCEDURES_FOLDER);
            }
        }
        return complete;
    }

    private ProcedureDefinition localProcedure(String dbName, String name) {
        try {
            return cache.loadProcedureUncached(dbName, name);
        } catch (MetadataReadException e) {
            return null;
        }
    }

    private ScheduleDefinition localSchedule(String dbName, String name) {
        try {
            return cache.loadScheduleUncached(dbName, name);
        } catch (MetadataReadException e) {
            return null;
        }
    }

    private boolean conformSchedules(AdminSnapshotPayload snapshot, HashMap<String, AdminDbEntry> snapshotDbs,
            UnreadableItems unreadable) throws Exception {
        var complete = true;
        final var desired = new HashMap<String, JsonObject>();
        for (final var entry : snapshot.getSchedules().entrySet()) {
            desired.put(entry.getKey(), entry.getValue().asJsonObject());
        }
        for (final var dbName : snapshotDbs.keySet()) {
            boolean changed;
            final var waitMillis = clusterConfig.replicationAckTimeoutMs();
            if (!locks.tryLockWrite(dbName, Globals.SCHEDULES_FOLDER, waitMillis)) {
                logger.warning("Skipping the schedules conform of " + dbName + ": its lock stayed held" + " for "
                        + waitMillis + "ms. The next round retries it.");
                complete = false;
                continue;
            }
            try {
                final var removal = removeAbsentDefinitions(dbName, AdminSnapshotKeys.schedules(dbName),
                        () -> fs.listScheduleNames(dbName), AdminSnapshotKeys::schedule, desired, unreadable, name -> {
                            fs.deleteSchedule(dbName, name);
                            cache.removeSchedule(dbName, name);
                        });
                complete &= removal.complete();
                changed = removal.removedAny();
                for (final var entry : desired.entrySet()) {
                    final var parts = entry.getKey().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX);
                    if (parts.length < 2 || !parts[0].equals(dbName)) {
                        continue;
                    }
                    final var definition = ScheduleDefinition.fromJsonObject(entry.getValue());
                    if (!definition.equals(localSchedule(dbName, parts[1]))) {
                        fs.writeSchedule(dbName, parts[1], eJson.toJson(entry.getValue()));
                        cache.removeSchedule(dbName, parts[1]);
                        changed = true;
                    }
                }
            } finally {
                locks.release(dbName, Globals.SCHEDULES_FOLDER);
            }
            if (changed) {
                scheduleRegistry.reload(dbName);
            }
        }
        return complete;
    }

    private List<TriggerDefinition> localTriggers(String dbName, String collName) {
        try {
            return cache.loadTriggersUncached(dbName, collName);
        } catch (MetadataReadException e) {
            return null;
        }
    }

    // Runs under the collection lock the caller already holds.
    private void conformTriggers(String dbName, String collName, JsonObject snapshotTriggers) throws Exception {
        final var key = Cache.getCollectionIdentifier(dbName, collName);
        final var desired = snapshotTriggers.has(key) && snapshotTriggers.get(key).isJsonArray()
                ? TriggerDefinition.fromJsonArray(snapshotTriggers.get(key).asJsonArray())
                : new ArrayList<TriggerDefinition>();
        final var current = localTriggers(dbName, collName);
        if (desired.equals(current)) {
            return;
        }
        if (desired.isEmpty()) {
            if (current != null) {
                fs.deleteTriggers(dbName, collName);
                cache.removeTriggers(dbName, collName);
            }
            return;
        }
        fs.writeTriggers(dbName, collName, eJson.toJson(TriggerDefinition.toFileJson(desired)));
        cache.removeTriggers(dbName, collName);
    }

    private boolean conformCollection(String dbName, String collName, java.util.Set<String> desiredIndexes,
            JsonObject desiredSchema, JsonObject snapshotTriggers, long epochAtStart, long snapshotIncarnation,
            UnreadableItems unreadable) throws Exception {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockDatabaseShared(dbName, waitMillis)) {
            logger.warning("Skipping the conform of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": its database stayed locked by a drop for " + waitMillis + "ms. The next round retries it.");
            return false;
        }
        try {
            return conformCollectionUnderDatabaseBarrier(dbName, collName, desiredIndexes, desiredSchema,
                    snapshotTriggers, epochAtStart, snapshotIncarnation, waitMillis, unreadable);
        } finally {
            locks.releaseDatabaseShared(dbName);
        }
    }

    private boolean conformCollectionUnderDatabaseBarrier(String dbName, String collName,
            java.util.Set<String> desiredIndexes, JsonObject desiredSchema, JsonObject snapshotTriggers,
            long epochAtStart, long snapshotIncarnation, long waitMillis, UnreadableItems unreadable) throws Exception {
        if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
            logger.warning("Skipping the conform of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": its write lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return false;
        }
        try {
            if (adminEpoch.current() != epochAtStart) {
                logger.warning("Skipping the conform of " + dbName + "|" + collName + ": a local admin op committed"
                        + " during it. The next round reconciles from the newer local state.");
                return false;
            }
            if (snapshotIncarnation != 0) {
                hybridClock.observe(snapshotIncarnation);
            }
            quarantine.quarantineStaleIncarnation(dbName, collName, snapshotIncarnation);
            if (!LeftoverFolders.moveAsideUnregisteredCollection(dbName, collName)) {
                return false;
            }
            fs.createCollectionFile(dbName, collName);
            final var localEntry = cache.getAdminCollectionEntry(dbName, collName);
            if (localEntry == null) {
                AdminOperationHelper.createPageCollections(dbName, collName);
                final var entry = new AdminCollEntry(dbName, collName);
                entry.setIncarnation(snapshotIncarnation);
                AdminOperationHelper.saveCollectionEntry(entry);
            } else if (localEntry.getIncarnation() != snapshotIncarnation && snapshotIncarnation != 0) {
                final var reincarnated = new AdminCollEntry(dbName, collName, new HashSet<>(localEntry.getIndexes()));
                reincarnated.setIncarnation(snapshotIncarnation);
                AdminOperationHelper.saveCollectionEntry(reincarnated);
            }
            final var existing = new HashSet<>(cache.getIndexesForCollection(dbName, collName));
            for (final var field : desiredIndexes) {
                if (!existing.contains(field)) {
                    fs.indexBuildMarkers().mark(dbName, collName, field);
                    IndexHelper.createIndex(dbName, collName, field);
                    AdminOperationHelper.saveNewIndex(dbName, collName, field);
                    fs.indexBuildMarkers().clear(dbName, collName, field);
                }
            }
            for (final var field : existing) {
                if (!desiredIndexes.contains(field)) {
                    fs.indexBuildMarkers().mark(dbName, collName, field);
                    IndexHelper.dropIndex(dbName, collName, field);
                    AdminOperationHelper.deleteIndex(dbName, collName, field);
                    fs.indexBuildMarkers().clear(dbName, collName, field);
                }
            }
            var complete = true;
            final var schemaKey = AdminSnapshotKeys.schema(dbName, collName);
            if (unreadable.contains(schemaKey)) {
                complete = skipUnreadable(schemaKey, unreadable);
            } else {
                conformSchema(dbName, collName, desiredSchema);
            }
            final var triggersKey = AdminSnapshotKeys.triggers(dbName, collName);
            if (unreadable.contains(triggersKey)) {
                complete &= skipUnreadable(triggersKey, unreadable);
            } else {
                conformTriggers(dbName, collName, snapshotTriggers);
            }
            return complete;
        } finally {
            locks.release(dbName, collName);
        }
    }

    private JsonObject localSchema(String dbName, String collName) {
        try {
            return cache.loadSchemaUncached(dbName, collName);
        } catch (MetadataReadException e) {
            return null;
        }
    }

    private void conformSchema(String dbName, String collName, JsonObject desiredSchema) throws Exception {
        final var current = localSchema(dbName, collName);
        if (desiredSchema != null) {
            if (!desiredSchema.equals(current)) {
                fs.writeCollectionSchema(dbName, collName, eJson.toJson(desiredSchema));
                cache.removeCollectionSchema(dbName, collName);
            }
        } else if (current != null) {
            fs.deleteCollectionSchema(dbName, collName);
            cache.removeCollectionSchema(dbName, collName);
        }
    }
}
