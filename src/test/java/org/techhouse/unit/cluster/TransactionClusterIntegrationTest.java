package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
import org.techhouse.cluster.msg.TxReplicationPayload;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.RollbackTransactionRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionClusterIntegrationTest {
    private static final String SECRET = "s";
    private final Configuration config = Configuration.getInstance();
    private final ClusterRouter router = IocContainer.get(ClusterRouter.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final PeerConnectionPool pool = IocContainer.get(PeerConnectionPool.class);
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
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
        TestUtils.resetClients();
        origEnabled = config.isClusterEnabled();
        origSecret = config.getClusterSecret();
        origTls = config.isClusterTlsEnabled();
        origAck = config.getReplicationAckTimeoutMs();
        origExpected = config.getClusterExpectedSize();
        TestUtils.setPrivateField(config, "clusterSecret", SECRET);
        TestUtils.setPrivateField(config, "clusterTlsEnabled", false);
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 1000L);
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

    private NodeAddress serverAddress() {
        return new NodeAddress("127.0.0.1", serverPort);
    }

    private static JsonObject doc(String id) {
        final var object = new JsonObject();
        object.add("_id", new JsonString(id));
        return object;
    }

    private ClusterMessage forwardTx(String sessionId, Object request) {
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_TX_REQUEST, SECRET, node("edge", 1),
                null);
        message.setForwardBody(ForwardBody.encode(eJson.toJson(request)));
        message.setActingUser("admin");
        message.setTxSessionId(sessionId);
        return message;
    }

    private SaveRequest saveRequest(String coll, String id) {
        final var request = new SaveRequest(TestGlobals.DB, coll);
        request.setObject(doc(id));
        request.set_id(id);
        return request;
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private void configureMembership(int expectedSize, NodeInfo self, NodeInfo... others) throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", true);
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
            final var coll = "tx-routed-" + i;
            if (!ownership.isOwner(TestGlobals.DB, coll)) {
                return coll;
            }
        }
        throw new IllegalStateException("no collection owned by the other node");
    }

    private UUID newClient() {
        final var socket = Mockito.mock(Socket.class);
        final var addr = Mockito.mock(InetAddress.class);
        Mockito.when(socket.getInetAddress()).thenReturn(addr);
        Mockito.when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    // ---------- owner-side FORWARD_TX handler ----------

    @Test
    public void test_forward_tx_save_then_commit_persists_and_clears_session() throws Exception {
        final var save = pool.request(serverAddress(), forwardTx("sess-1", saveRequest(TestGlobals.COLL, "fwd-1")),
                2000);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, save.getType());
        final var commit = pool.request(serverAddress(), forwardTx("sess-1", new CommitTransactionRequest()), 2000);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, commit.getType());
        assertEquals(OperationStatus.OK, findStatus("fwd-1"));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void test_forward_tx_rollback_discards_and_clears_session() throws Exception {
        pool.request(serverAddress(), forwardTx("sess-2", saveRequest(TestGlobals.COLL, "fwd-2")), 2000);
        final var rollback = pool.request(serverAddress(), forwardTx("sess-2", new RollbackTransactionRequest()), 2000);
        assertEquals(ClusterMessageType.FORWARD_RESPONSE, rollback.getType());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("fwd-2"));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void test_forward_tx_with_unparseable_body_is_error() throws Exception {
        final var message = new ClusterMessage(null, ClusterMessageType.FORWARD_TX_REQUEST, SECRET, node("edge", 1),
                null);
        message.setForwardBody(ForwardBody.encode("not-json"));
        message.setTxSessionId("sess-bad");
        final var response = pool.request(serverAddress(), message, 2000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
    }

    // ---------- replica-side REPLICATE_TX handler ----------

    @Test
    public void test_replicate_tx_applies_batch() throws Exception {
        final var entry = new ReplicationPayload(TestGlobals.DB, TestGlobals.COLL, ReplicationOp.UPSERT,
                List.of(doc("rep-tx")), null, List.of(7L));
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE_TX, SECRET, node("edge", 1), null);
        message.setTxReplication(new TxReplicationPayload(List.of(entry)));
        final var response = pool.request(serverAddress(), message, 2000);
        assertEquals(ClusterMessageType.REPLICATE_TX_ACK, response.getType());
        assertEquals(OperationStatus.OK, findStatus("rep-tx"));
    }

    @Test
    public void test_replicate_tx_with_empty_batch_is_nacked() throws Exception {
        final var message = new ClusterMessage(null, ClusterMessageType.REPLICATE_TX, SECRET, node("edge", 1), null);
        message.setTxReplication(new TxReplicationPayload());
        final var response = pool.request(serverAddress(), message, 2000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
    }

    // ---------- edge routing ----------

    @Test
    public void test_transaction_write_binds_and_forwards_to_owner() throws Exception {
        configureMembership(2, node("self", 19990), node("other", serverPort));
        final var coll = collectionOwnedByOther();
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);

        final var request = saveRequest(coll, "routed");
        final var relayed = router.forward(request, eJson.toJson(request), true, "admin", clientId);

        // The owner (this JVM, sharing the ownership singleton) rejects it as cross-owner, but the response
        // travelled through the edge->owner forward path, and the edge is now bound to the remote owner.
        assertNotNull(relayed);
        assertTrue(relayed.contains("421-2"), "expected forwarded owner response, got: " + relayed);
        assertEquals(serverAddress().toString(), clientTracker.getTransactionOwner(clientId));

        // Teardown forwards a rollback to the owner, releasing its session.
        router.teardownTransaction(clientId);
    }

    @Test
    public void test_transaction_write_to_unreachable_owner_returns_503_4() throws Exception {
        configureMembership(2, node("self", 19990), node("other", 1));
        final var coll = collectionOwnedByOther();
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var request = saveRequest(coll, "unreachable");
        final var relayed = router.forward(request, eJson.toJson(request), true, "admin", clientId);
        assertNotNull(relayed);
        assertTrue(relayed.contains("503-4"), "expected OWNER_UNREACHABLE, got: " + relayed);
    }

    @Test
    public void test_start_and_unbound_read_run_locally() throws Exception {
        configureMembership(2, node("self", 19990), node("other", serverPort));
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        // START is not forwarded.
        assertNull(router.forward(new StartTransactionRequest(), "{}", true, "admin", clientId));
        // A read before the first write runs locally (no binding yet).
        final var read = new FindByIdRequest(TestGlobals.DB, collectionOwnedByOther());
        read.set_id("x");
        assertNull(router.forward(read, eJson.toJson(read), true, "admin", clientId));
    }

    @Test
    public void test_bound_local_transaction_runs_locally() throws Exception {
        configureMembership(1, node("self", serverPort));
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var request = saveRequest(TestGlobals.COLL, "local-bound");
        // Self owns everything in a single-node ring: the write binds local and runs locally (null).
        assertNull(router.forward(request, eJson.toJson(request), true, "admin", clientId));
        assertTrue(clientTracker.isTransactionBound(clientId));
        assertNull(clientTracker.getTransactionOwner(clientId));
    }

    @Test
    public void test_teardown_is_noop_for_unbound_transaction() throws Exception {
        configureMembership(1, node("self", serverPort));
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        // Not bound to a remote owner: teardown does nothing and does not throw.
        router.teardownTransaction(clientId);
    }
}
