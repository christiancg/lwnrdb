package org.techhouse.cluster;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.config.Globals;
import org.techhouse.data.TriggerDefinition;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ex.MetadataReadException;
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
        stop(clusterConfig.antiEntropyIntervalMs());
    }

    public void stop(long awaitMillis) {
        started = false;
        adminSyncCompleted.set(false);
        publishSyncState();
        sweep.stop(awaitMillis);
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
        if (adminEpoch.isUnreadable()) {
            return;
        }
        boolean answered;
        try {
            AdminSnapshotPayload best = null;
            final var self = membershipService.getSelf();
            var bestEpoch = adminEpoch.current();
            var bestNodeId = self != null ? self.getNodeId() : null;
            final var peers = membershipService.membershipView().peers(self);
            answered = peers.isEmpty();
            for (final var member : peers) {
                final var snapshot = requestSnapshot(member.address());
                if (snapshot == null) {
                    continue;
                }
                answered = true;
                final var snapshotNodeId = snapshot.getNodeId() != null ? snapshot.getNodeId() : member.getNodeId();
                if (outranks(snapshot.getEpoch(), snapshotNodeId, bestEpoch, bestNodeId)) {
                    bestEpoch = snapshot.getEpoch();
                    bestNodeId = snapshotNodeId;
                    best = snapshot;
                }
            }
            if (best != null && wouldEmptyThisNode(best)) {
                logger.warning("Refusing to conform to the admin snapshot of " + bestNodeId + " at epoch " + bestEpoch
                        + ": it lists no databases while this node holds some, which would unregister every one"
                        + " of them and delete every user");
            } else if (best != null) {
                conformer.conform(best);
                adminEpoch.adopt(best.getEpoch());
                antiEntropyService.reconcileNow();
            }
            if (answered) {
                adminSyncCompleted.set(true);
            }
        } finally {
            publishSyncState();
        }
    }

    private boolean wouldEmptyThisNode(AdminSnapshotPayload snapshot) {
        final var offered = snapshot.getDatabases();
        return (offered == null || offered.isEmpty()) && !cache.getAllAdminDbEntries().isEmpty();
    }

    private static boolean outranks(long epoch, String nodeId, long bestEpoch, String bestNodeId) {
        if (epoch != bestEpoch) {
            return epoch > bestEpoch;
        }
        if (nodeId == null || bestNodeId == null) {
            return false;
        }
        return nodeId.compareTo(bestNodeId) > 0;
    }

    private <T> T readable(String dbName, String name, String kind, Supplier<T> loader) {
        try {
            return loader.get();
        } catch (MetadataReadException e) {
            logger.warning("Leaving the " + kind + " of " + dbName + Globals.COLL_IDENTIFIER_SEPARATOR + name
                    + " out of the admin snapshot: " + e.getMessage());
            return null;
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
                final var procedure = readable(dbName, procedureName, "procedure",
                        () -> cache.loadProcedureUncached(dbName, procedureName));
                if (procedure != null) {
                    procedures.add(Cache.getCollectionIdentifier(dbName, procedureName), procedure.toJsonObject());
                }
            }
            for (final var scheduleName : fs.listScheduleNames(dbName)) {
                final var schedule = readable(dbName, scheduleName, "schedule",
                        () -> cache.loadScheduleUncached(dbName, scheduleName));
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
                    final var schema = readable(dbName, collName, "schema",
                            () -> cache.loadSchemaUncached(dbName, collName));
                    if (schema != null) {
                        schemas.add(collEntry.get_id(), schema);
                    }
                    final var collTriggers = readable(dbName, collName, "triggers",
                            () -> cache.loadTriggersUncached(dbName, collName));
                    if (collTriggers != null && !collTriggers.isEmpty()) {
                        triggers.add(collEntry.get_id(), TriggerDefinition.toJsonArray(collTriggers));
                    }
                }
            }
        }
        final var users = new ArrayList<JsonObject>();
        for (final var userEntry : cache.getAllAdminUserEntries()) {
            users.add(userEntry.getData());
        }
        final var payload = new AdminSnapshotPayload(adminEpoch.current(), databases, collections, users, schemas,
                procedures, triggers, schedules);
        final var self = membershipService.getSelf();
        payload.setNodeId(self != null ? self.getNodeId() : null);
        return payload;
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
