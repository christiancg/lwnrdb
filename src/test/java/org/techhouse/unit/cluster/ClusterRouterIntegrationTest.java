package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterRouter;
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
import org.techhouse.config.Configuration;
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.ReplicatedApplyHelper;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ClusterRouterIntegrationTest {
    private static final String SECRET = "s";
    private final Configuration config = Configuration.getInstance();
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final EJson eJson = IocContainer.get(EJson.class);
    private final FileSystem fs = IocContainer.get(FileSystem.class);
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

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
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

    private String collectionOwnedByOther() {
        for (var i = 0; i < 500; i++) {
            final var coll = "routed-" + i;
            if (!ownership.isOwner(TestGlobals.DB, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    private void createCollection(String coll) throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
    }

    private String rawFind(String coll, String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id(id);
        return eJson.toJson(request);
    }

    private String rawSave(String coll, String id) {
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(doc(id));
        request.set_id(id);
        return eJson.toJson(request);
    }

    @Test
    public void test_router_forwards_read_to_owner() throws Exception {
        configureMembership(2, node("self", 19990), node("other", serverPort));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        ReplicatedApplyHelper
                .apply(new ReplicationPayload(TestGlobals.DB, coll, ReplicationOp.UPSERT, List.of(doc("f1")), null));

        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id("f1");
        final var relayed = router.forward(request, rawFind(coll, "f1"), false, null);

        assertNotNull(relayed);
        assertTrue(relayed.contains("f1"), "expected forwarded read response to contain the document, got: " + relayed);
    }

    @Test
    public void test_router_executes_locally_when_self_owns() throws Exception {
        configureMembership(1, node("self", serverPort));
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(doc("local"));
        assertNull(router.forward(request, rawSave(TestGlobals.COLL, "local"), false, null));
    }

    @Test
    public void test_read_falls_back_to_local_when_owner_unreachable() throws Exception {
        configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        final var request = new FindByIdRequest(TestGlobals.DB, coll);
        request.set_id("x");
        assertNull(router.forward(request, rawFind(coll, "x"), false, null));
    }

    @Test
    public void test_write_errors_when_owner_unreachable() throws Exception {
        configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(doc("y"));
        final var relayed = router.forward(request, rawSave(coll, "y"), false, null);
        assertNotNull(relayed);
        assertTrue(relayed.contains("503-4"), "expected OWNER_UNREACHABLE, got: " + relayed);
    }

    @Test
    public void test_forward_request_handler_executes_write_on_owner() throws Exception {
        configureMembership(1, node("self", serverPort));
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_REQUEST, SECRET, null, null);
        message.setForwardBody(ForwardBody.encode(rawSave(TestGlobals.COLL, "h1")));
        final var response = pool.request(new NodeAddress("127.0.0.1", serverPort), message, 3000);

        assertEquals(ClusterMessageType.FORWARD_RESPONSE, response.getType());
        assertTrue(ForwardBody.decode(response.getForwardBody()).contains("h1"));
        final var find = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        find.set_id("h1");
        assertEquals(OperationStatus.OK, processor.processMessage(find).getStatus());
    }
}
