package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;

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
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final ExecutorService reconcileExecutor = Executors.newSingleThreadExecutor(r -> {
        final var t = new Thread(r, "cluster-admin-anti-entropy");
        t.setDaemon(true);
        return t;
    });
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
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
        publishSyncState();
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
        publishSyncState();
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

    // Gossiped so peers can keep a script off a node whose admin state is not caught up yet
    // (cluster/ScriptPlacement). Inert while the service is stopped, which is what keeps the standalone path
    // and the manually-wired tests eligible.
    private void publishSyncState() {
        membershipService.setAdminSyncing(!hasCompletedAdminSync());
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
            for (final var member : membershipService.membershipView().peers(self)) {
                final var snapshot = requestSnapshot(member.address());
                if (snapshot != null && snapshot.getEpoch() > bestEpoch) {
                    bestEpoch = snapshot.getEpoch();
                    best = snapshot;
                }
            }
            if (best != null) {
                conformer.conform(best);
                adminEpoch.adopt(best.getEpoch());
                antiEntropyService.reconcileNow();
            }
        } finally {
            adminSyncCompleted.set(true);
            publishSyncState();
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
        final var schedules = new JsonObject();
        for (final var dbName : cache.getUserDatabaseNames()) {
            for (final var procedureName : fs.listProcedureNames(dbName)) {
                final var procedure = cache.loadProcedureUncached(dbName, procedureName);
                if (procedure != null) {
                    procedures.add(Cache.getCollectionIdentifier(dbName, procedureName), procedure.toJsonObject());
                }
            }
            for (final var scheduleName : fs.listScheduleNames(dbName)) {
                final var schedule = cache.loadScheduleUncached(dbName, scheduleName);
                if (schedule != null) {
                    schedules.add(Cache.getCollectionIdentifier(dbName, scheduleName), schedule.toJsonObject());
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
                triggers, schedules);
    }

    private AdminSnapshotPayload requestSnapshot(NodeAddress address) {
        final var message = PeerRequest.message(ClusterMessageType.ADMIN_SNAPSHOT);
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
