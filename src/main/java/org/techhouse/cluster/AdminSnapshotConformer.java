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
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;

// Converges this node's admin metadata onto a peer snapshot: writes what differs, drops what the
// snapshot does not have. The admin epoch is the ordering, so there is no per-record version, and
// every step is idempotent so the periodic sweep does not rewrite an already-converged node.
final class AdminSnapshotConformer {
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final org.techhouse.ops.CompiledProcedureCache compiledProcedures = IocContainer
            .get(org.techhouse.ops.CompiledProcedureCache.class);
    private final ScheduleRegistry scheduleRegistry = IocContainer.get(ScheduleRegistry.class);

    void conform(AdminSnapshotPayload snapshot) throws Exception {
        final var snapshotUsers = conformUsers(snapshot);
        removeAbsentUsers(snapshotUsers);
        final var snapshotDbs = conformDatabases(snapshot);
        conformProcedures(snapshot, snapshotDbs);
        conformSchedules(snapshot, snapshotDbs);
        final var snapshotColls = conformCollections(snapshot, snapshotDbs);
        dropAbsentCollections(snapshotDbs, snapshotColls);
        dropAbsentDatabases(snapshotDbs);
    }

    private HashSet<String> conformUsers(AdminSnapshotPayload snapshot) throws Exception {
        final var snapshotUsers = new HashSet<String>();
        for (final var userJson : snapshot.getUsers()) {
            final var user = AdminUserEntry.fromJsonObject(userJson);
            snapshotUsers.add(user.get_id());
            AdminOperationHelper.saveUserEntry(user);
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
            // Creating it only when the entry is absent would let one failed create stay broken forever.
            fs.createDatabaseFolder(db.get_id());
            if (cache.getAdminDbEntry(db.get_id()) == null) {
                AdminOperationHelper.saveDatabaseEntry(
                        new AdminDbEntry(db.get_id(), new ArrayList<>(), new ArrayList<>(db.getOwners())));
            } else {
                AdminOperationHelper.updateDatabaseOwners(db.get_id(), db.getOwners());
            }
        }
        return snapshotDbs;
    }

    private HashSet<String> conformCollections(AdminSnapshotPayload snapshot, HashMap<String, AdminDbEntry> snapshotDbs)
            throws Exception {
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
            conformCollection(dbName, collName, coll.getIndexes(), desiredSchema, snapshot.getTriggers());
        }
        return snapshotColls;
    }

    // Converges each database's stored procedures to the snapshot: write when different, delete the ones
    // the snapshot does not have. No per-record version comparison - the admin epoch is the ordering, the
    // same rule collection schemas follow.
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
                    if (!definition.equals(cache.loadProcedureUncached(dbName, parts[1]))) {
                        fs.writeProcedure(dbName, parts[1], eJson.toJson(entry.getValue()));
                        cache.removeProcedure(dbName, parts[1]);
                    }
                }
            } finally {
                locks.release(dbName, Globals.PROCEDURES_FOLDER);
            }
        }
    }

    // Converges each database's schedules to the snapshot, on the same terms as conformProcedures: write
    // when different, delete the ones the snapshot does not have, ordering by the admin epoch rather than
    // any per-record version. The registry is rebuilt for a database that changed, so the scheduler picks
    // up what anti-entropy brought in without waiting for scheduleRefreshMs.
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
                    if (!definition.equals(cache.loadScheduleUncached(dbName, parts[1]))) {
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

    // Converges the collection's trigger file/cache to the snapshot, under the collection lock the caller
    // already holds. Idempotent, so the periodic sweep does not rewrite an already-matching list.
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
            JsonObject desiredSchema, JsonObject snapshotTriggers) throws Exception {
        locks.lock(dbName, collName);
        try {
            // Same reason as the database folder above: idempotent, and run on every sweep so a collection
            // whose directory went missing under a live admin entry is repaired rather than left unwritable.
            fs.createCollectionFile(dbName, collName);
            if (cache.getAdminCollectionEntry(dbName, collName) == null) {
                AdminOperationHelper.createPageCollections(dbName, collName);
                AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(dbName, collName));
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

    // Converges the collection's schema file/cache to the snapshot: write when different, delete when the
    // snapshot has none. Idempotent, so the periodic sweep does not rewrite an already-matching schema.
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

    // Drops orphan collections (removed while their database is kept). Collections of an entirely-removed
    // database are left to dropDatabase, which deletes the whole folder in one shot.
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
        locks.lock(dbName, collName);
        var dropped = false;
        try {
            if (fs.deleteCollectionFiles(dbName, collName)) {
                cache.evictCollection(dbName, collName);
                AdminOperationHelper.deleteCollectionEntry(dbName, collName);
                AdminOperationHelper.deletePageCollections(dbName, collName);
                listenManager.unregisterAllForCollection(dbName, collName);
                dropped = true;
            }
        } finally {
            locks.release(dbName, collName);
            if (dropped) {
                locks.removeLock(dbName, collName);
            }
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
        try {
            for (final var collName : collNames) {
                locks.lock(dbName, collName);
                lockedColls.add(collName);
            }
            if (fs.deleteDatabase(dbName)) {
                cache.evictDatabase(dbName);
                for (final var collName : lockedColls) {
                    locks.removeLock(dbName, collName);
                }
                AdminOperationHelper.deleteDatabaseEntry(dbName);
                listenManager.unregisterAllForDatabase(dbName);
            }
        } finally {
            for (final var collName : lockedColls) {
                locks.release(dbName, collName);
            }
        }
    }
}
