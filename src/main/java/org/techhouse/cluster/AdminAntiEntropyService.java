package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.ProcedureDefinition;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.log.Logger;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.IndexHelper;

/**
 * Admin/DDL anti-entropy: a node that was down during a structural change (CREATE/DROP DATABASE/COLLECTION/
 * INDEX, SET_DATABASE_OWNERS, user/permission ops) catches up on rejoin. On a membership change (and on a
 * periodic sweep) each node pulls the authoritative admin snapshot from live peers, keeps the highest-epoch
 * one, and — if it is ahead of this node's own epoch — conforms local databases, collections, indexes, owners
 * and users to it, then triggers a document pass to repopulate freshly-materialized collections. Authority is
 * decided by the single cluster-wide {@link AdminEpoch}, so a stale rejoining node never overwrites live
 * state. All of this is a no-op unless clustering is enabled.
 */
public class AdminAntiEntropyService implements MembershipListener {
    private final Logger logger = Logger.logFor(AdminAntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final org.techhouse.ops.CompiledProcedureCache compiledProcedures = IocContainer
            .get(org.techhouse.ops.CompiledProcedureCache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final ExecutorService reconcileExecutor = Executors.newSingleThreadExecutor(r -> {
        final var t = new Thread(r, "cluster-admin-anti-entropy");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    // False until this node has completed one admin reconciliation since being started, so a node that just
    // became the admin coordinator does not commit admin ops on a stale base (see ClusterAdminHelper.guard).
    private final AtomicBoolean adminSyncCompleted = new AtomicBoolean(false);
    // The gate is only enforced once the service is started (production wiring); otherwise it is inert.
    private volatile boolean started;
    private ScheduledExecutorService periodicScheduler;

    public void start() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        started = true;
        if (clusterConfig.antiEntropyIntervalMs() <= 0) {
            return;
        }
        periodicScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            final var t = new Thread(r, "cluster-admin-anti-entropy-sweep");
            t.setDaemon(true);
            return t;
        });
        final var interval = clusterConfig.antiEntropyIntervalMs();
        periodicScheduler.scheduleAtFixedRate(this::scheduleReconcile, interval, interval, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        started = false;
        adminSyncCompleted.set(false);
        if (periodicScheduler != null) {
            periodicScheduler.shutdownNow();
            periodicScheduler = null;
        }
    }

    // A coordinator must not serve coordinated admin ops until it has completed one reconciliation since
    // joining. Inert (returns true) until the service is started, so the single-node/standalone path and the
    // manually-wired tests are unaffected.
    public boolean hasCompletedAdminSync() {
        return !started || adminSyncCompleted.get();
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        scheduleReconcile();
    }

    private void scheduleReconcile() {
        if (scheduled.compareAndSet(false, true)) {
            reconcileExecutor.submit(() -> {
                scheduled.set(false);
                try {
                    reconcile();
                } catch (Exception e) {
                    logger.warning("Admin anti-entropy reconciliation failed: " + e.getMessage());
                }
            });
        }
    }

    public void reconcile() throws Exception {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        try {
            AdminSnapshotPayload best = null;
            var bestEpoch = adminEpoch.current();
            final var self = membershipService.getSelf();
            for (final var member : membershipService.membershipView().aliveMembers()) {
                if (self != null && member.getNodeId().equals(self.getNodeId())) {
                    continue;
                }
                final var snapshot = requestSnapshot(member.address());
                if (snapshot != null && snapshot.getEpoch() > bestEpoch) {
                    bestEpoch = snapshot.getEpoch();
                    best = snapshot;
                }
            }
            if (best != null) {
                conform(best);
                adminEpoch.adopt(best.getEpoch());
                antiEntropyService.reconcileNow();
            }
        } finally {
            adminSyncCompleted.set(true);
        }
    }

    // Builds this node's authoritative admin snapshot: its epoch plus every user database, collection (with
    // its _id and indexes) and user (full record incl. password hash).
    public AdminSnapshotPayload buildSnapshot() {
        final var databases = new ArrayList<JsonObject>();
        for (final var dbEntry : cache.getAllAdminDbEntries()) {
            databases.add(dbEntry.getData());
        }
        final var collections = new ArrayList<JsonObject>();
        final var schemas = new JsonObject();
        final var procedures = new JsonObject();
        final var triggers = new JsonObject();
        for (final var dbName : cache.getUserDatabaseNames()) {
            for (final var procedureName : fs.listProcedureNames(dbName)) {
                final var procedure = cache.loadProcedureUncached(dbName, procedureName);
                if (procedure != null) {
                    procedures.add(Cache.getCollectionIdentifier(dbName, procedureName), procedure.toJsonObject());
                }
            }
            for (final var collName : cache.getCollectionNamesForDatabase(dbName)) {
                final var collEntry = cache.getAdminCollectionEntry(dbName, collName);
                if (collEntry != null) {
                    final var json = collEntry.getData().deepCopy();
                    json.addProperty(Globals.PK_FIELD, collEntry.get_id());
                    collections.add(json);
                    final var schema = cache.loadSchemaUncached(dbName, collName);
                    if (schema != null) {
                        schemas.add(collEntry.get_id(), schema);
                    }
                    final var collTriggers = cache.loadTriggersUncached(dbName, collName);
                    if (!collTriggers.isEmpty()) {
                        triggers.add(collEntry.get_id(), TriggerDefinition.toJsonArray(collTriggers));
                    }
                }
            }
        }
        final var users = new ArrayList<JsonObject>();
        for (final var userEntry : cache.getAllAdminUserEntries()) {
            users.add(userEntry.getData());
        }
        return new AdminSnapshotPayload(adminEpoch.current(), databases, collections, users, schemas, procedures,
                triggers);
    }

    private void conform(AdminSnapshotPayload snapshot) throws Exception {
        final var snapshotUsers = conformUsers(snapshot);
        removeAbsentUsers(snapshotUsers);
        final var snapshotDbs = conformDatabases(snapshot);
        conformProcedures(snapshot, snapshotDbs);
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
            if (cache.getAdminDbEntry(db.get_id()) == null) {
                fs.createDatabaseFolder(db.get_id());
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
            if (cache.getAdminCollectionEntry(dbName, collName) == null) {
                fs.createCollectionFile(dbName, collName);
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

    private AdminSnapshotPayload requestSnapshot(NodeAddress address) {
        final var message = new ClusterMessage(null, ClusterMessageType.ADMIN_SNAPSHOT, clusterConfig.secret(),
                membershipService.getSelf(), null);
        try {
            final var response = pool.request(address, message, clusterConfig.replicationAckTimeoutMs());
            if (response.getType() == ClusterMessageType.ADMIN_SNAPSHOT_ACK) {
                return response.getAdminSnapshot();
            }
            logger.warning("Admin snapshot request to " + address + " not acknowledged: " + response.getErrorMessage());
            return null;
        } catch (Exception e) {
            logger.warning("Admin snapshot request to " + address + " failed: " + e.getMessage());
            return null;
        }
    }
}
