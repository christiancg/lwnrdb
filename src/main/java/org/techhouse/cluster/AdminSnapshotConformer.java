package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;

// The admin epoch is the ordering, so there is no per-record version, and every step is
// idempotent so the periodic sweep does not rewrite an already-converged node.
final class AdminSnapshotConformer {
    private final Logger logger = Logger.logFor(AdminSnapshotConformer.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final org.techhouse.ops.CompiledProcedureCache compiledProcedures = IocContainer
            .get(org.techhouse.ops.CompiledProcedureCache.class);
    private final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);

    void conform(AdminSnapshotPayload snapshot) throws Exception {
        final var epochAtStart = adminEpoch.current();
        final var snapshotUsers = conformUsers(snapshot);
        removeAbsentUsers(snapshotUsers);
        final var snapshotDbs = conformDatabases(snapshot);
        conformProcedures(snapshot, snapshotDbs);
        conformSchedules(snapshot, snapshotDbs);
        final var snapshotColls = conformCollections(snapshot, snapshotDbs, epochAtStart);
        if (adminEpoch.current() != epochAtStart) {
            logger.warning("Skipping the quarantine phase of the admin conform: a local admin op committed"
                    + " during it. The next round reconciles from the newer local state.");
            return;
        }
        dropAbsentCollections(snapshotDbs, snapshotColls);
        dropAbsentDatabases(snapshotDbs);
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

    private HashMap<String, AdminDbEntry> conformDatabases(AdminSnapshotPayload snapshot) throws Exception {
        final var snapshotDbs = new HashMap<String, AdminDbEntry>();
        for (final var dbJson : snapshot.getDatabases()) {
            final var db = AdminDbEntry.fromJsonObject(dbJson);
            snapshotDbs.put(db.get_id(), db);
        }
        for (final var db : snapshotDbs.values()) {
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

    private HashSet<String> conformCollections(AdminSnapshotPayload snapshot, HashMap<String, AdminDbEntry> snapshotDbs,
            long epochAtStart) throws Exception {
        final var snapshotColls = new HashSet<String>();
        for (final var collJson : snapshot.getCollections()) {
            final var coll = AdminCollEntry.fromJsonObject(collJson);
            final var parts = coll.get_id().split(Globals.COLL_IDENTIFIER_SEPARATOR_REGEX, 2);
            final var dbName = parts[0];
            final var collName = parts[1];
            if (!snapshotDbs.containsKey(dbName)) {
                continue;
            }
            snapshotColls.add(coll.get_id());
            final var schemaEl = snapshot.getSchemas().get(coll.get_id());
            final var desiredSchema = schemaEl != null && schemaEl.isJsonObject() ? schemaEl.asJsonObject() : null;
            conformCollection(dbName, collName, coll.getIndexes(), desiredSchema, snapshot.getTriggers(), epochAtStart,
                    coll.getIncarnation());
        }
        return snapshotColls;
    }

    private void conformProcedures(AdminSnapshotPayload snapshot, HashMap<String, AdminDbEntry> snapshotDbs)
            throws Exception {
        final var desired = new HashMap<String, JsonObject>();
        for (final var entry : snapshot.getProcedures().entrySet()) {
            desired.put(entry.getKey(), entry.getValue().asJsonObject());
        }
        for (final var dbName : snapshotDbs.keySet()) {
            locks.lock(dbName, Globals.PROCEDURES_FOLDER);
            try {
                for (final var existingName : new ArrayList<>(fs.listProcedureNames(dbName))) {
                    if (!desired.containsKey(Cache.getCollectionIdentifier(dbName, existingName))) {
                        fs.deleteProcedure(dbName, existingName);
                        cache.removeProcedure(dbName, existingName);
                        compiledProcedures.invalidateProcedure(dbName, existingName);
                    }
                }
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

    private void conformSchedules(AdminSnapshotPayload snapshot, HashMap<String, AdminDbEntry> snapshotDbs)
            throws Exception {
        final var desired = new HashMap<String, JsonObject>();
        for (final var entry : snapshot.getSchedules().entrySet()) {
            desired.put(entry.getKey(), entry.getValue().asJsonObject());
        }
        for (final var dbName : snapshotDbs.keySet()) {
            var changed = false;
            locks.lock(dbName, Globals.SCHEDULES_FOLDER);
            try {
                for (final var existingName : new ArrayList<>(fs.listScheduleNames(dbName))) {
                    if (!desired.containsKey(Cache.getCollectionIdentifier(dbName, existingName))) {
                        fs.deleteSchedule(dbName, existingName);
                        cache.removeSchedule(dbName, existingName);
                        changed = true;
                    }
                }
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
    }

    // Runs under the collection lock the caller already holds.
    private void conformTriggers(String dbName, String collName, JsonObject snapshotTriggers) throws Exception {
        final var key = Cache.getCollectionIdentifier(dbName, collName);
        final var desired = snapshotTriggers.has(key) && snapshotTriggers.get(key).isJsonArray()
                ? TriggerDefinition.fromJsonArray(snapshotTriggers.get(key).asJsonArray())
                : new ArrayList<TriggerDefinition>();
        if (desired.equals(cache.loadTriggersUncached(dbName, collName))) {
            return;
        }
        if (desired.isEmpty()) {
            fs.deleteTriggers(dbName, collName);
            cache.removeTriggers(dbName, collName);
            return;
        }
        fs.writeTriggers(dbName, collName, eJson.toJson(TriggerDefinition.toFileJson(desired)));
        cache.removeTriggers(dbName, collName);
    }

    private void conformCollection(String dbName, String collName, java.util.Set<String> desiredIndexes,
            JsonObject desiredSchema, JsonObject snapshotTriggers, long epochAtStart, long snapshotIncarnation)
            throws Exception {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
            logger.warning("Skipping the conform of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": its write lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return;
        }
        try {
            if (adminEpoch.current() != epochAtStart) {
                logger.warning("Skipping the conform of " + dbName + "|" + collName + ": a local admin op committed"
                        + " during it. The next round reconciles from the newer local state.");
                return;
            }
            quarantineStaleIncarnation(dbName, collName, snapshotIncarnation);
            fs.createCollectionFile(dbName, collName);
            final var localEntry = cache.getAdminCollectionEntry(dbName, collName);
            if (localEntry == null) {
                AdminOperationHelper.createPageCollections(dbName, collName);
                final var entry = new AdminCollEntry(dbName, collName);
                entry.setIncarnation(snapshotIncarnation);
                AdminOperationHelper.saveCollectionEntry(entry);
            } else if (localEntry.getIncarnation() != snapshotIncarnation && snapshotIncarnation != 0) {
                localEntry.setIncarnation(snapshotIncarnation);
                AdminOperationHelper.saveCollectionEntry(localEntry);
            }
            final var existing = new HashSet<>(cache.getIndexesForCollection(dbName, collName));
            for (final var field : desiredIndexes) {
                if (!existing.contains(field)) {
                    IndexHelper.createIndex(dbName, collName, field);
                    AdminOperationHelper.saveNewIndex(dbName, collName, field);
                }
            }
            for (final var field : existing) {
                if (!desiredIndexes.contains(field)) {
                    IndexHelper.dropIndex(dbName, collName, field);
                    AdminOperationHelper.deleteIndex(dbName, collName, field);
                }
            }
            conformSchema(dbName, collName, desiredSchema);
            conformTriggers(dbName, collName, snapshotTriggers);
        } finally {
            locks.release(dbName, collName);
        }
    }

    private void quarantineStaleIncarnation(String dbName, String collName, long snapshotIncarnation) throws Exception {
        final var localEntry = cache.getAdminCollectionEntry(dbName, collName);
        if (localEntry == null || snapshotIncarnation == 0 || localEntry.getIncarnation() == 0
                || localEntry.getIncarnation() >= snapshotIncarnation) {
            return;
        }
        final var stale = localEntry.getIncarnation();
        cache.evictCollection(dbName, collName);
        AdminOperationHelper.deleteCollectionEntry(dbName, collName);
        AdminOperationHelper.deletePageCollections(dbName, collName);
        listenManager.unregisterAllForCollection(dbName, collName);
        final var moved = fs.quarantineCollectionFiles(dbName, collName, stale);
        logger.warning("Quarantined collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                + ": its documents belong to incarnation " + stale + ", which was dropped, and the cluster has since"
                + " re-created the name as incarnation " + snapshotIncarnation + ". They were "
                + (moved ? "moved aside on disk" : "left on disk") + " and the collection is now empty here.");
    }

    private void conformSchema(String dbName, String collName, JsonObject desiredSchema) throws Exception {
        final var current = cache.loadSchemaUncached(dbName, collName);
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

    // Collections of an entirely-removed database are left to dropDatabase, which deletes the whole
    // folder in one shot.
    private void dropAbsentCollections(HashMap<String, AdminDbEntry> snapshotDbs, HashSet<String> snapshotColls)
            throws Exception {
        for (final var dbName : new ArrayList<>(cache.getUserDatabaseNames())) {
            if (!snapshotDbs.containsKey(dbName)) {
                continue;
            }
            for (final var collName : new ArrayList<>(cache.getCollectionNamesForDatabase(dbName))) {
                if (!snapshotColls.contains(Cache.getCollectionIdentifier(dbName, collName))) {
                    dropCollection(dbName, collName);
                }
            }
        }
    }

    private void dropCollection(String dbName, String collName) throws Exception {
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
            logger.warning("Skipping the quarantine of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": its write lock stayed held for " + waitMillis + "ms. The next round retries it.");
            return;
        }
        try {
            cache.evictCollection(dbName, collName);
            AdminOperationHelper.deleteCollectionEntry(dbName, collName);
            AdminOperationHelper.deletePageCollections(dbName, collName);
            listenManager.unregisterAllForCollection(dbName, collName);
            logger.warning("Quarantined collection " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + collName
                    + ": it is absent from the winning admin snapshot. Its documents are left on disk and it no"
                    + " longer serves reads or writes until an operator reinstates or removes it.");
        } finally {
            locks.release(dbName, collName);
            locks.removeLock(dbName, collName);
        }
    }

    private void dropAbsentDatabases(HashMap<String, AdminDbEntry> snapshotDbs) throws Exception {
        for (final var dbName : new ArrayList<>(cache.getUserDatabaseNames())) {
            if (!snapshotDbs.containsKey(dbName)) {
                dropDatabase(dbName);
            }
        }
    }

    private void dropDatabase(String dbName) throws Exception {
        final var dbEntry = cache.getAdminDbEntry(dbName);
        final var collNames = dbEntry != null ? new ArrayList<>(dbEntry.getCollections()) : new ArrayList<String>();
        Collections.sort(collNames);
        final var lockedColls = new ArrayList<String>();
        final var waitMillis = clusterConfig.replicationAckTimeoutMs();
        try {
            for (final var collName : collNames) {
                if (!locks.tryLockWrite(dbName, collName, waitMillis)) {
                    logger.warning("Skipping the quarantine of database " + dbName + ": the write lock of " + collName
                            + " stayed held for " + waitMillis + "ms. The next round retries it.");
                    return;
                }
                lockedColls.add(collName);
            }
            cache.evictDatabase(dbName);
            AdminOperationHelper.deleteDatabaseEntry(dbName);
            compiledProcedures.invalidateDatabase(dbName);
            scheduleRegistry.removeDatabase(dbName);
            listenManager.unregisterAllForDatabase(dbName);
            logger.warning("Quarantined database " + dbName
                    + ": it is absent from the winning admin snapshot. Its documents are left on disk and it no"
                    + " longer serves reads or writes until an operator reinstates or removes it.");
        } finally {
            for (final var collName : lockedColls) {
                locks.release(dbName, collName);
            }
            for (final var collName : lockedColls) {
                locks.removeLock(dbName, collName);
            }
        }
    }
}
