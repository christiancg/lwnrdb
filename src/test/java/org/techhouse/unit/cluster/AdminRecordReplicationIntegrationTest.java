package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.ClusterTestHarness.SECRET;
import static org.techhouse.test.ClusterTestHarness.node;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.HybridClock;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.admin.AdminRecord;
import org.techhouse.cluster.msg.AdminSnapshotPayload;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.admin.AdminRecordKey;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.ops.req.ReindexRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminRecordReplicationIntegrationTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final HybridClock clock = IocContainer.get(HybridClock.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        cluster.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static JsonObject bodyOf(DbEntry entry) {
        final var body = entry.getData().deepCopy();
        body.addProperty(Globals.PK_FIELD, entry.get_id());
        return body;
    }

    private AdminRecord collection(String collName) {
        return new AdminRecord(AdminRecordKey.collection(TestGlobals.DB, collName), clock.next(),
                bodyOf(new AdminCollEntry(TestGlobals.DB, collName)));
    }

    private AdminRecord bob() {
        return new AdminRecord(AdminRecordKey.user("bob"), clock.next(),
                bodyOf(new AdminUserEntry("bob", "hash-bob", false, Set.of(), new HashMap<>(), new HashMap<>())));
    }

    private static ClusterMessage replicateAdmin(AdminRecord... records) {
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE_ADMIN, SECRET, null, null);
        message.setAdminSnapshot(new AdminSnapshotPayload(List.of(records)));
        return message;
    }

    @Test
    public void test_a_replicated_collection_record_is_installed_on_a_peer() throws Exception {
        cluster.configureRemoteCoordinator();
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(collection("repl-coll")), 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "repl-coll"));
    }

    @Test
    public void test_a_replicated_database_record_carries_its_owners() throws Exception {
        cluster.configureRemoteCoordinator();
        final var record = new AdminRecord(AdminRecordKey.database("clusterdb"), clock.next(),
                bodyOf(new AdminDbEntry("clusterdb", List.of(), List.of("alice"))));
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(record), 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        assertTrue(cache.getAdminDbEntry("clusterdb").getOwners().contains("alice"));
    }

    @Test
    public void test_a_record_whose_parent_is_unknown_is_not_acknowledged() throws Exception {
        cluster.configureRemoteCoordinator();
        final var orphan = new AdminRecord(AdminRecordKey.collection("nodb", "coll"), clock.next(),
                bodyOf(new AdminCollEntry("nodb", "coll")));
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(orphan), 3000);
        assertEquals(ClusterMessageType.ERROR, ack.getType());
        assertNull(cache.getAdminCollectionEntry("nodb", "coll"));
    }

    @Test
    public void test_a_replicated_user_record_keeps_the_coordinators_hash() throws Exception {
        cluster.configureRemoteCoordinator();
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(bob()), 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        assertEquals("hash-bob", cache.getAdminUserEntry("bob").getPasswordHash());
    }

    @Test
    public void test_a_replicated_user_tombstone_removes_the_user() throws Exception {
        AdminOperationHelper
                .saveUserEntry(new AdminUserEntry("carol", "hash", false, Set.of(), new HashMap<>(), new HashMap<>()));
        cluster.configureRemoteCoordinator();
        final var tombstone = AdminRecord.tombstone(AdminRecordKey.user("carol"), clock.next());
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(tombstone), 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        assertNull(cache.getAdminUserEntry("carol"));
    }

    @Test
    public void test_broadcast_admin_single_node_is_met() throws Exception {
        cluster.configureMembership(1, node("self", 19990));
        assertEquals(ReplicationOutcome.QUORUM_MET,
                replicator.broadcastAdmin(new AdminSnapshotPayload(List.of(collection("single-coll")))));
    }

    @Test
    public void test_broadcast_admin_reaches_quorum_with_reachable_peer() throws Exception {
        cluster.configureRemoteCoordinator();
        assertEquals(ReplicationOutcome.QUORUM_MET,
                replicator.broadcastAdmin(new AdminSnapshotPayload(List.of(collection("peer-coll")))));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "peer-coll"));
    }

    @Test
    public void test_broadcast_admin_times_out_when_peer_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("peer", 1));
        assertEquals(ReplicationOutcome.TIMEOUT,
                replicator.broadcastAdmin(new AdminSnapshotPayload(List.of(collection("nope-coll")))));
    }

    @Test
    public void test_records_are_not_replicated_when_clustering_is_off() throws Exception {
        TestUtils.setPrivateField(Configuration.getInstance(), "clusterEnabled", false);
        assertEquals(ReplicationOutcome.NOT_CLUSTERED, coordinator.replicateAdminRecords(List.of()));
    }

    @Test
    public void test_a_reindex_broadcast_rebuilds_on_a_peer() throws Exception {
        cluster.configureRemoteCoordinator();
        final var message = new ClusterMessage(null, ClusterMessageType.REINDEX_BROADCAST, SECRET, null, null);
        message.setForwardBody(
                ForwardBody.encode(eJson.toJson(new ReindexRequest(TestGlobals.DB, TestGlobals.COLL, null))));
        final var ack = pool.request(cluster.serverAddress(), message, 3000);
        assertEquals(ClusterMessageType.REINDEX_BROADCAST_ACK, ack.getType());
    }

    @Test
    public void test_router_forwards_admin_op_and_a_receiver_that_does_not_coordinate_refuses_it() throws Exception {
        cluster.configureRemoteCoordinator();
        final var request = new CreateCollectionRequest(TestGlobals.DB, "routed-coll");
        final var relayed = router.forward(request, eJson.toJson(request), false, "alice", null);
        assertNotNull(relayed);
        assertTrue(relayed.contains("421-1"), relayed);
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "routed-coll"));
    }

    @Test
    public void test_router_forwards_user_op_to_coordinator() throws Exception {
        cluster.configureRemoteCoordinator();
        final var request = new DeleteUserRequest();
        request.setUsername("ghost");
        assertNotNull(router.forward(request, eJson.toJson(request), false, "alice", null));
    }

    @Test
    public void test_router_executes_admin_locally_when_this_node_is_coordinator() throws Exception {
        cluster.configureMembership(1, node("self", 19990));
        final var request = new CreateCollectionRequest(TestGlobals.DB, "local-coll");
        assertNull(router.forward(request, eJson.toJson(request), false, "alice", null));
    }

    @Test
    public void test_admin_op_rejected_without_quorum() throws Exception {
        cluster.configureMembership(3, node("self", 19990));
        final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "noquorum-coll"));
        assertEquals("503-2", response.getErrorCode());
        assertNull(cache.getAdminCollectionEntry(TestGlobals.DB, "noquorum-coll"));
    }

    @Test
    public void test_single_node_coordinator_creates_and_replicates() throws Exception {
        cluster.configureMembership(1, node("self", 19990));
        final var response = processor.processMessage(new CreateCollectionRequest(TestGlobals.DB, "coord-coll"));
        assertEquals(OperationStatus.OK, response.getStatus());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "coord-coll"));
    }
}
