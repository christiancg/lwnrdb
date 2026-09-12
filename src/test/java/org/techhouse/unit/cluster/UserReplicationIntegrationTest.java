package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestUtils;

public class UserReplicationIntegrationTest {
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private final Configuration config = Configuration.getInstance();
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);

    private static AdminUserEntry user(String username) {
        return new AdminUserEntry(username, "hash-" + username, false, Set.of(), new HashMap<>(), new HashMap<>());
    }

    private static ReplicationPayload upsert(AdminUserEntry entry) {
        return new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME, ReplicationOp.UPSERT,
                List.of(entry.getData()), null);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        cluster.start();
    }

    @AfterEach
    public void tearDown() throws Exception {
        cluster.stop();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private ClusterMessage replicateUser(ReplicationPayload payload) {
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE_USER, SECRET, null, null);
        message.setReplication(payload);
        return message;
    }

    @Test
    public void test_replicate_user_upsert_applies_identical_record() throws Exception {
        cluster.configureRemoteCoordinator();
        final var ack = pool.request(cluster.serverAddress(), replicateUser(upsert(user("bob"))), 3000);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType());
        final var stored = cache.getAdminUserEntry("bob");
        assertNotNull(stored);
        assertEquals("hash-bob", stored.getPasswordHash());
    }

    @Test
    public void test_replicate_user_delete_removes_record() throws Exception {
        AdminOperationHelper.saveUserEntry(user("carol"));
        cluster.configureRemoteCoordinator();
        final var payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                ReplicationOp.DELETE, null, List.of("carol"));
        final var ack = pool.request(cluster.serverAddress(), replicateUser(payload), 3000);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType());
        assertNull(cache.getAdminUserEntry("carol"));
    }

    @Test
    public void test_broadcast_user_single_node_is_met() throws Exception {
        cluster.configureMembership(1, node("self", 19990));
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastUser(upsert(user("dave"))));
    }

    @Test
    public void test_broadcast_user_reaches_quorum_with_reachable_peer() throws Exception {
        cluster.configureRemoteCoordinator();
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastUser(upsert(user("erin"))));
        assertNotNull(cache.getAdminUserEntry("erin"));
    }

    @Test
    public void test_broadcast_user_times_out_when_peer_unreachable() throws Exception {
        cluster.configureMembership(2, node("self", 19990), node("peer", 1));
        assertEquals(ReplicationOutcome.TIMEOUT, replicator.broadcastUser(upsert(user("frank"))));
    }

    @Test
    public void test_replicate_user_op_via_coordinator() throws Exception {
        AdminOperationHelper.saveUserEntry(user("grace"));
        cluster.configureMembership(1, node("self", 19990));
        assertEquals(ReplicationOutcome.QUORUM_MET, coordinator.replicateUserOp("grace", false));
    }

    @Test
    public void test_replicate_user_op_not_applicable_when_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        assertEquals(ReplicationOutcome.NOT_APPLICABLE, coordinator.replicateUserOp("nobody", false));
    }

    @Test
    public void test_router_forwards_user_op_to_coordinator() throws Exception {
        cluster.configureRemoteCoordinator();
        final var request = new DeleteUserRequest();
        request.setUsername("ghost");
        assertNotNull(router.forward(request, eJson.toJson(request), false, "alice", null));
    }
}
