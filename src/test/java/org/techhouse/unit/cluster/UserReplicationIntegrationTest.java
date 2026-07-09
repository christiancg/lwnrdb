package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.ClusterRouter;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.req.DeleteUserRequest;
import org.techhouse.test.TestUtils;

public class UserReplicationIntegrationTest {
    private static final String SECRET = "s";
    private final Configuration config = Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final Replicator replicator = IocContainer.get(Replicator.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final Cache cache = IocContainer.get(Cache.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private ClusterServer server;
    private int serverPort;
    private boolean origEnabled;
    private String origSecret;
    private boolean origTls;
    private long origAck;
    private int origExpected;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

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
        origEnabled = config.isClusterEnabled();
        origSecret = config.getClusterSecret();
        origTls = config.isClusterTlsEnabled();
        origAck = config.getReplicationAckTimeoutMs();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 1500L);
        server = new ClusterServer(0, "127.0.0.1", null);
        server.start();
        serverPort = server.getPort();
    }

    @AfterEach
    public void tearDown() throws Exception {
        pool.closeAll();
        server.stop();
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterSecret", origSecret);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", origTls);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origAck);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void configureMembership(int expectedSize, NodeInfo self, NodeInfo... others) throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", expectedSize);
        final var members = new ConcurrentHashMap<String, NodeInfo>();
        members.put(self.getNodeId(), self);
        for (final var other : others) {
            members.put(other.getNodeId(), other);
        }
        TestUtils.setPrivateField(membershipService, "members", members);
        TestUtils.setPrivateField(membershipService, "self", self);
        ownership.setSelfNodeId(self.getNodeId());
        ownership.onMembershipChanged(membershipService.membershipView());
    }

    private void configureRemoteCoordinator() throws Exception {
        for (var i = 0; i < 500; i++) {
            configureMembership(2, node("self", 19990), node("coord-" + i, serverPort));
            if (!ownership.isAdminCoordinator()) {
                return;
            }
        }
        throw new IllegalStateException("could not configure self as a non-coordinator");
    }

    private ClusterMessage replicateUser(ReplicationPayload payload) {
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE_USER, SECRET, null, null);
        message.setReplication(payload);
        return message;
    }

    @Test
    public void test_replicate_user_upsert_applies_identical_record() throws Exception {
        configureRemoteCoordinator();
        final var ack = pool.request(new NodeAddress("127.0.0.1", serverPort), replicateUser(upsert(user("bob"))),
                3000);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType());
        final var stored = cache.getAdminUserEntry("bob");
        assertNotNull(stored);
        assertEquals("hash-bob", stored.getPasswordHash());
    }

    @Test
    public void test_replicate_user_delete_removes_record() throws Exception {
        AdminOperationHelper.saveUserEntry(user("carol"));
        configureRemoteCoordinator();
        final var payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                ReplicationOp.DELETE, null, List.of("carol"));
        final var ack = pool.request(new NodeAddress("127.0.0.1", serverPort), replicateUser(payload), 3000);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType());
        assertNull(cache.getAdminUserEntry("carol"));
    }

    @Test
    public void test_broadcast_user_single_node_is_met() throws Exception {
        configureMembership(1, node("self", 19990));
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastUser(upsert(user("dave"))));
    }

    @Test
    public void test_broadcast_user_reaches_quorum_with_reachable_peer() throws Exception {
        configureRemoteCoordinator();
        assertEquals(ReplicationOutcome.QUORUM_MET, replicator.broadcastUser(upsert(user("erin"))));
        assertNotNull(cache.getAdminUserEntry("erin"));
    }

    @Test
    public void test_broadcast_user_times_out_when_peer_unreachable() throws Exception {
        configureMembership(2, node("self", 19990), node("peer", 1));
        assertEquals(ReplicationOutcome.TIMEOUT, replicator.broadcastUser(upsert(user("frank"))));
    }

    @Test
    public void test_replicate_user_op_via_coordinator() throws Exception {
        AdminOperationHelper.saveUserEntry(user("grace"));
        configureMembership(1, node("self", 19990));
        assertEquals(ReplicationOutcome.QUORUM_MET, coordinator.replicateUserOp("grace", false));
    }

    @Test
    public void test_replicate_user_op_not_applicable_when_disabled() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", false);
        assertEquals(ReplicationOutcome.NOT_APPLICABLE, coordinator.replicateUserOp("nobody", false));
    }

    @Test
    public void test_router_forwards_user_op_to_coordinator() throws Exception {
        configureRemoteCoordinator();
        final var request = new DeleteUserRequest();
        request.setUsername("ghost");
        assertNotNull(router.forward(request, eJson.toJson(request), false, "alice"));
    }
}
