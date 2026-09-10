package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.techhouse.test.ClusterTestHarness.SECRET;
import static org.techhouse.test.ClusterTestHarness.node;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.ops.req.CreateDatabaseRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminReplicationIntegrationTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);

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

    private ClusterMessage replicateAdmin(String rawJson, String actingUser) {
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE_ADMIN, SECRET, null, null);
        message.setForwardBody(ForwardBody.encode(rawJson));
        message.setActingUser(actingUser);
        return message;
    }

    @Test
    public void test_replicate_admin_applies_create_collection_on_a_peer() throws Exception {
        cluster.configureRemoteCoordinator();
        final var raw = eJson.toJson(new CreateCollectionRequest(TestGlobals.DB, "repl-coll"));
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(raw, null), 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "repl-coll"));
    }

    @Test
    public void test_replicate_admin_create_database_assigns_acting_user_as_owner() throws Exception {
        cluster.configureRemoteCoordinator();
        final var raw = eJson.toJson(new CreateDatabaseRequest("clusterdb"));
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(raw, "alice"), 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        final var dbEntry = cache.getAdminDbEntry("clusterdb");
        assertNotNull(dbEntry);
        assertTrue(dbEntry.getOwners().contains("alice"));
    }

    @Test
    public void test_replicate_admin_nacks_when_op_fails() throws Exception {
        cluster.configureRemoteCoordinator();
        // The test database already exists, so re-creating it fails and the peer must NACK (not ACK).
        final var raw = eJson.toJson(new CreateDatabaseRequest(TestGlobals.DB));
        final var ack = pool.request(cluster.serverAddress(), replicateAdmin(raw, "alice"), 3000);
        assertEquals(ClusterMessageType.ERROR, ack.getType());
    }

    @Test
    public void test_broadcast_admin_single_node_is_met() throws Exception {
        cluster.configureMembership(1, node("self", 19990));
        final var raw = eJson.toJson(new CreateCollectionRequest(TestGlobals.DB, "single-coll"));
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastAdmin(raw, null));
    }

    @Test
    public void test_broadcast_admin_reaches_quorum_with_reachable_peer() throws Exception {
        cluster.configureRemoteCoordinator();
        final var raw = eJson.toJson(new CreateCollectionRequest(TestGlobals.DB, "peer-coll"));
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastAdmin(raw, null));
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "peer-coll"));
    }

    @Test
    public void test_broadcast_admin_times_out_when_peer_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("peer", 1));
        final var raw = eJson.toJson(new CreateCollectionRequest(TestGlobals.DB, "nope-coll"));
        assertEquals(ReplicationOutcome.TIMEOUT, replicator.broadcastAdmin(raw, null));
    }

    @Test
    public void test_router_forwards_admin_op_to_coordinator() throws Exception {
        cluster.configureRemoteCoordinator();
        final var request = new CreateCollectionRequest(TestGlobals.DB, "routed-coll");
        final var relayed = router.forward(request, eJson.toJson(request), false, "alice", null);
        assertNotNull(relayed);
        assertNotNull(cache.getAdminCollectionEntry(TestGlobals.DB, "routed-coll"));
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
