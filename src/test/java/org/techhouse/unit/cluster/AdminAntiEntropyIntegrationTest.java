package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.cluster.AdminEpoch;
import org.techhouse.cluster.ClusterServer;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeAddress;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.PeerConnectionPool;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.cluster.msg.ForwardBody;
import org.techhouse.cluster.msg.ReplicationOp;
import org.techhouse.cluster.msg.ReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminUserEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.req.CreateCollectionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminAntiEntropyIntegrationTest {
    private static final String SECRET = "s";
    private final org.techhouse.config.Configuration config = org.techhouse.config.Configuration.getInstance();
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final AdminEpoch adminEpoch = IocContainer.get(AdminEpoch.class);
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

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origEnabled = config.isClusterEnabled();
        origSecret = config.getClusterSecret();
        origTls = config.isClusterTlsEnabled();
        origAck = config.getReplicationAckTimeoutMs();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 1500L);
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
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
        TestUtils.setPrivateField(adminEpoch, "epoch", 0L);
        ownership.setSelfNodeId(null);
        ownership.onMembershipChanged(new MembershipView(List.of()));
        TestUtils.setPrivateField(membershipService, "members", new ConcurrentHashMap<>());
        TestUtils.setPrivateField(membershipService, "self", null);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void configureMembership(NodeInfo self, NodeInfo... others) throws Exception {
        TestUtils.setPrivateField(config, "clusterExpectedSize", 2);
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
            configureMembership(node("self", 19990), node("coord-" + i, serverPort));
            if (!ownership.isAdminCoordinator()) {
                return;
            }
        }
        throw new IllegalStateException("could not configure self as a non-coordinator");
    }

    private ClusterMessage message(ClusterMessageType type) {
        return new ClusterMessage(null, type, SECRET, null, null);
    }

    @Test
    public void test_admin_snapshot_over_the_wire_returns_state_and_epoch() throws Exception {
        TestUtils.setPrivateField(adminEpoch, "epoch", 3L);
        final var ack = pool.request(new NodeAddress("127.0.0.1", serverPort),
                message(ClusterMessageType.ADMIN_SNAPSHOT), 3000);
        assertEquals(ClusterMessageType.ADMIN_SNAPSHOT_ACK, ack.getType());
        final var snapshot = ack.getAdminSnapshot();
        assertNotNull(snapshot);
        assertEquals(3L, snapshot.getEpoch());
        assertTrue(snapshot.getDatabases().stream()
                .anyMatch(db -> TestGlobals.DB.equals(db.get(Globals.PK_FIELD).asJsonString().getValue())));
        assertTrue(snapshot.getCollections().stream()
                .anyMatch(coll -> Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)
                        .equals(coll.get(Globals.PK_FIELD).asJsonString().getValue())));
    }

    @Test
    public void test_replicate_admin_adopts_shipped_epoch() throws Exception {
        configureRemoteCoordinator();
        final var raw = eJson.toJson(new CreateCollectionRequest(TestGlobals.DB, "epoch-coll"));
        final var message = message(ClusterMessageType.REPLICATE_ADMIN);
        message.setForwardBody(ForwardBody.encode(raw));
        message.setAdminEpoch(7L);
        final var ack = pool.request(new NodeAddress("127.0.0.1", serverPort), message, 3000);
        assertEquals(ClusterMessageType.REPLICATE_ADMIN_ACK, ack.getType());
        assertEquals(7L, adminEpoch.current());
    }

    @Test
    public void test_replicate_user_adopts_shipped_epoch() throws Exception {
        final var user = new AdminUserEntry("wireuser", "hash", false, Set.of(), Map.of(), Map.of());
        final var payload = new ReplicationPayload(Globals.ADMIN_DB_NAME, Globals.ADMIN_USERS_COLLECTION_NAME,
                ReplicationOp.UPSERT, List.of(user.getData()), null);
        final var message = message(ClusterMessageType.REPLICATE_USER);
        message.setReplication(payload);
        message.setAdminEpoch(9L);
        final var ack = pool.request(new NodeAddress("127.0.0.1", serverPort), message, 3000);
        assertEquals(ClusterMessageType.REPLICATE_USER_ACK, ack.getType());
        assertEquals(9L, adminEpoch.current());
        assertNotNull(cache.getAdminUserEntry("wireuser"));
    }
}
