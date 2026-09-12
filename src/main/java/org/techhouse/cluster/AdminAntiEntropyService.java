package org.techhouse.cluster;

import java.util.ArrayList;
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

public class AdminAntiEntropyService implements MembershipListener {
    private final Logger logger = Logger.logFor(AdminAntiEntropyService.class);
    private final ClusterConfig clusterConfig = IocContainer.get(ClusterConfig.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final CoalescingSweep sweep = new CoalescingSweep(logger, "cluster-admin-anti-entropy",
            "Admin anti-entropy", this::reconcile);
    private final AdminSnapshotConformer conformer = new AdminSnapshotConformer();
    // False until this node has completed one admin reconciliation since being started, so a node that just
    // became the admin coordinator does not commit admin ops on a stale base (see ClusterAdminHelper.guard).
    private final AtomicBoolean adminSyncCompleted = new AtomicBoolean(false);
    private volatile boolean started;

    public void start() {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        started = true;
        publishSyncState();
        sweep.startPeriodic(clusterConfig.antiEntropyIntervalMs());
    }

    public void stop() {
        started = false;
        adminSyncCompleted.set(false);
        publishSyncState();
        sweep.stopPeriodic();
    }

    public boolean hasCompletedAdminSync() {
        return !started || adminSyncCompleted.get();
    }

    // Gossiped so peers can keep a script off a node whose admin state is not caught up yet
    // (cluster/ScriptPlacement).
    private void publishSyncState() {
        membershipService.setAdminSyncing(!hasCompletedAdminSync());
    }

    @Override
    public void onMembershipChanged(MembershipView view) {
        if (!clusterConfig.isEnabled()) {
            return;
        }
        sweep.schedule();
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
