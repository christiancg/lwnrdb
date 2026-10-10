package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminAntiEntropyService;
import org.techhouse.cluster.AntiEntropyService;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.admin.AdminRecord;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyServiceTest {
    private final AdminAntiEntropyService service = IocContainer.get(AdminAntiEntropyService.class);
    private final AntiEntropyService antiEntropyService = IocContainer.get(AntiEntropyService.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final Configuration config = Configuration.getInstance();
    private PeerConnectionPool realPool;
    private PeerConnectionPool realDocPool;
    private PeerConnectionPool mockPool;
    private boolean origEnabled;
    private long origInterval;
    private int origExpected;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origInterval = config.getAntiEntropyIntervalMs();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterExpectedSize", 2);
        ownership.setSelfNodeId("self");
        ownership.onMembershipChanged(new MembershipView(List.of(node("peer", 19991))));
        realPool = TestUtils.getPrivateField(service, "pool", PeerConnectionPool.class);
        realDocPool = TestUtils.getPrivateField(antiEntropyService, "pool", PeerConnectionPool.class);
        mockPool = mock(PeerConnectionPool.class);
        TestUtils.setPrivateField(service, "pool", mockPool);
        TestUtils.setPrivateField(antiEntropyService, "pool", mockPool);
        TestUtils.setPrivateField(service, "started", true);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        withPeers("peer");
        TestUtils.setPrivateField(membershipService, "self", node("self", 19990));
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(service, "pool", realPool);
        TestUtils.setPrivateField(antiEntropyService, "pool", realDocPool);
        TestUtils.setPrivateField(service, "started", false);
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(false));
        TestUtils.setPrivateField(service, "wasCoordinator", false);
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "antiEntropyIntervalMs", origInterval);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void withPeers(String... peerIds) throws Exception {
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        members.put("self", node("self", 19990));
        var port = 19991;
        for (final var peerId : peerIds) {
            members.put(peerId, node(peerId, port++));
        }
        TestUtils.setPrivateField(membershipService, "members", members);
    }

    private AdminRecord newDatabase() {
        return newDatabase(clock.next());
    }

    private static AdminRecord newDatabase(long version) {
        final var body = new AdminDbEntry("newdb", new ArrayList<>(), new ArrayList<>()).getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, "newdb");
        return new AdminRecord(AdminRecordKey.database("newdb"), version, body);
    }

    private AdminRecord orphanCollection() {
        final var orphanBody = new JsonObject();
        orphanBody.addProperty(Globals.PK_FIELD, "nodb|coll");
        return new AdminRecord(AdminRecordKey.collection("nodb", "coll"), clock.next(), orphanBody);
    }

    private static ClusterMessage ack(AdminRecord... records) {
        final var ack = new ClusterMessage();
        ack.setType(ClusterMessageType.ADMIN_SNAPSHOT_ACK);
        ack.setAdminSnapshot(new AdminSnapshotPayload(List.of(records)));
        return ack;
    }

    private void stubSnapshot(AdminRecord... records) throws Exception {
        when(mockPool.request(any(), any(), anyLong())).thenReturn(ack(records));
    }

    private void becomeCoordinator(boolean coordinator) {
        final var view = new MembershipView(List.of(node(coordinator ? "self" : "peer", 19990)));
        ownership.onMembershipChanged(view);
        service.onMembershipChanged(view);
    }

    private void drainRounds() throws Exception {
        final var sweep = TestUtils.getPrivateField(service, "sweep", Object.class);
        final var executor = TestUtils.getPrivateField(sweep, "reconcileExecutor", ExecutorService.class);
        try {
            executor.submit(() -> null).get(3, TimeUnit.SECONDS);
        } catch (RejectedExecutionException stopped) {
            assertTrue(executor.isShutdown());
        }
    }

    private boolean syncingFlag() throws Exception {
        return TestUtils.getPrivateField(membershipService, "adminSyncing", Boolean.class);
    }

    @Test
    public void test_a_peer_snapshot_is_merged_and_completes_the_sync() throws Exception {
        membershipService.setAdminSyncing(true);
        stubSnapshot(newDatabase());

        service.reconcile();

        assertNotNull(cache.getAdminDbEntry("newdb"));
        assertTrue(service.hasCompletedAdminSync());
        assertFalse(syncingFlag());
        assertFalse(service.isSyncing());
    }

    @Test
    public void test_a_tombstone_from_a_peer_quarantines_the_local_record() throws Exception {
        stubSnapshot(AdminRecord.tombstone(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL), clock.next()));

        service.reconcile();

        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_snapshot_that_cannot_be_fully_merged_leaves_the_node_syncing() throws Exception {
        membershipService.setAdminSyncing(true);
        stubSnapshot(orphanCollection());

        service.reconcile();

        assertFalse(service.hasCompletedAdminSync());
        assertTrue(syncingFlag());
        assertTrue(service.isSyncing());
    }

    @Test
    public void test_an_unreachable_peer_leaves_the_node_syncing() throws Exception {
        membershipService.setAdminSyncing(true);
        when(mockPool.request(any(), any(), anyLong())).thenThrow(new RuntimeException("unreachable"));

        service.reconcile();

        assertFalse(service.hasCompletedAdminSync());
        assertTrue(syncingFlag());
    }

    @Test
    public void test_a_peer_answering_with_an_error_is_not_a_sync() throws Exception {
        final var error = new ClusterMessage();
        error.setType(ClusterMessageType.ERROR);
        error.setErrorMessage("boom");
        when(mockPool.request(any(), any(), anyLong())).thenReturn(error);

        service.reconcile();

        assertFalse(service.hasCompletedAdminSync());
    }

    @Test
    public void test_an_interrupted_snapshot_request_stops_the_round() throws Exception {
        when(mockPool.request(any(), any(), anyLong())).thenThrow(new InterruptedException());
        try {
            service.reconcile();

            assertFalse(service.hasCompletedAdminSync());
        } finally {
            assertTrue(Thread.interrupted());
        }
    }

    @Test
    public void test_a_single_node_cluster_syncs_without_peers() throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", 1);
        withPeers();

        service.reconcile();

        assertTrue(service.hasCompletedAdminSync());
    }

    @Test
    public void test_a_lone_node_of_a_larger_cluster_does_not_sync_alone() throws Exception {
        withPeers();

        service.reconcile();

        assertFalse(service.hasCompletedAdminSync());
    }

    @Test
    public void test_one_complete_peer_of_five_nodes_is_not_a_sync() throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", 5);
        withPeers("p1", "p2", "p3", "p4");
        when(mockPool.request(any(), any(), anyLong())).thenReturn(ack(newDatabase()))
                .thenThrow(new RuntimeException("unreachable"));

        service.reconcile();

        assertNotNull(cache.getAdminDbEntry("newdb"));
        assertFalse(service.hasCompletedAdminSync());
        assertTrue(service.isSyncing());
    }

    @Test
    public void test_a_majority_of_complete_peers_is_a_sync() throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", 5);
        withPeers("p1", "p2", "p3", "p4");
        final var record = newDatabase();
        when(mockPool.request(any(), any(), anyLong())).thenReturn(ack(record), ack(record))
                .thenThrow(new RuntimeException("unreachable"));

        service.reconcile();

        assertTrue(service.hasCompletedAdminSync());
        assertFalse(service.isSyncing());
    }

    @Test
    public void test_an_incomplete_merge_does_not_count_toward_the_majority() throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", 4);
        withPeers("p1", "p2", "p3");
        when(mockPool.request(any(), any(), anyLong())).thenReturn(ack(newDatabase()), ack(orphanCollection()))
                .thenThrow(new RuntimeException("unreachable"));

        service.reconcile();

        assertFalse(service.hasCompletedAdminSync());
    }

    @Test
    public void test_becoming_coordinator_clears_the_sync() throws Exception {
        becomeCoordinator(false);
        drainRounds();
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(true));
        becomeCoordinator(false);
        drainRounds();
        assertTrue(service.hasCompletedAdminSync());

        becomeCoordinator(true);
        drainRounds();
        assertFalse(service.hasCompletedAdminSync());
        assertTrue(syncingFlag());

        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(true));
        becomeCoordinator(false);
        drainRounds();
        assertTrue(service.hasCompletedAdminSync());
        becomeCoordinator(true);
        drainRounds();
        assertFalse(service.hasCompletedAdminSync());
    }

    @Test
    public void test_staying_coordinator_keeps_the_sync() throws Exception {
        becomeCoordinator(true);
        drainRounds();
        TestUtils.setPrivateField(service, "adminSyncCompleted", new AtomicBoolean(true));

        becomeCoordinator(true);
        becomeCoordinator(true);
        drainRounds();

        assertTrue(service.hasCompletedAdminSync());
        assertFalse(service.isSyncing());
    }

    @Test
    public void test_a_round_that_straddles_the_handover_does_not_sync() throws Exception {
        when(mockPool.request(any(), any(), anyLong())).thenAnswer(_ -> {
            becomeCoordinator(true);
            return ack(newDatabase());
        }).thenThrow(new RuntimeException("unreachable"));

        service.reconcile();
        drainRounds();

        assertNotNull(cache.getAdminDbEntry("newdb"));
        assertFalse(service.hasCompletedAdminSync());
        assertTrue(syncingFlag());
    }

    @Test
    public void test_a_resynced_coordinator_has_observed_the_missed_version() throws Exception {
        becomeCoordinator(true);
        drainRounds();
        final var missed = clock.current() + 1_000_000;
        stubSnapshot(newDatabase(missed));

        service.reconcile();

        assertTrue(service.hasCompletedAdminSync());
        assertTrue(clock.next() > missed);
    }

    @Test
    public void test_reconcile_is_a_no_op_when_clustering_is_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        stubSnapshot(newDatabase());

        service.reconcile();
        service.reconcileSoon();

        assertNull(cache.getAdminDbEntry("newdb"));
        assertFalse(service.isSyncing());
    }

    @Test
    public void test_the_snapshot_carries_every_admin_record() throws Exception {
        AdminOperationHelper
                .saveUserEntry(new AdminUserEntry("snapuser", "h", false, Set.of(), new HashMap<>(), new HashMap<>()));

        final var ids = service.buildSnapshot().getRecords().stream().map(record -> record.key().id()).toList();

        assertTrue(ids.contains(AdminRecordKey.database(TestGlobals.DB).id()));
        assertTrue(ids.contains(AdminRecordKey.collection(TestGlobals.DB, TestGlobals.COLL).id()));
        assertTrue(ids.contains(AdminRecordKey.user("snapuser").id()));
    }

    @Test
    public void test_start_marks_this_node_as_syncing_and_stop_clears_it() throws Exception {
        TestUtils.setPrivateField(config, "antiEntropyIntervalMs", 0L);
        TestUtils.setPrivateField(service, "started", false);

        service.start();
        assertTrue(syncingFlag());

        service.stop();
        assertFalse(syncingFlag());
    }

    @Test
    public void test_a_membership_change_runs_a_round() throws Exception {
        TestUtils.setPrivateField(config, "antiEntropyIntervalMs", 3600000L);
        stubSnapshot(newDatabase());
        service.start();
        try {
            service.onMembershipChanged(membershipService.membershipView());
            final var sweep = TestUtils.getPrivateField(service, "sweep", Object.class);
            final var executor = TestUtils.getPrivateField(sweep, "reconcileExecutor", ExecutorService.class);
            executor.submit(() -> null).get(3, TimeUnit.SECONDS);

            assertTrue(service.hasCompletedAdminSync());
            assertEquals("newdb", cache.getAdminDbEntry("newdb").get_id());
        } finally {
            service.stop();
        }
    }
}
