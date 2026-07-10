package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.techhouse.cache.Cache;
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
import org.techhouse.data.admin.AdminCollEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.fs.FileSystem;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.Tx2pcLog;
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
    private final FileSystem fs = IocContainer.get(FileSystem.class);
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
        message.setTxId(UUID.nameUUIDFromBytes(sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        return message;
    }

    private ClusterMessage control(ClusterMessageType type, String sessionId, String dtxId) {
        final var message = new ClusterMessage(null, type, SECRET, node("self", serverPort), null);
        message.setTxSessionId(sessionId);
        message.setTxId(dtxId);
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

    private void createCollection(String coll) throws Exception {
        AdminOperationHelper.saveCollectionEntry(new AdminCollEntry(TestGlobals.DB, coll));
        fs.createCollectionFile(TestGlobals.DB, coll);
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

    // ---------- owner-side 2PC handlers ----------

    @Test
    public void test_prepare_then_commit_tx_over_wire() throws Exception {
        configureMembership(1, node("self", serverPort));
        pool.request(serverAddress(), forwardTx("tpc-1", saveRequest(TestGlobals.COLL, "tpc-doc-1")), 2000);
        final var dtxId = UUID.nameUUIDFromBytes("tpc-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        assertEquals(ClusterMessageType.PREPARE_TX_ACK,
                pool.request(serverAddress(), control(ClusterMessageType.PREPARE_TX, "tpc-1", dtxId), 2000).getType());
        assertEquals(ClusterMessageType.COMMIT_TX_ACK,
                pool.request(serverAddress(), control(ClusterMessageType.COMMIT_TX, "tpc-1", dtxId), 2000).getType());
        assertEquals(OperationStatus.OK, findStatus("tpc-doc-1"));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void test_prepare_then_abort_tx_over_wire() throws Exception {
        configureMembership(1, node("self", serverPort));
        pool.request(serverAddress(), forwardTx("tpc-2", saveRequest(TestGlobals.COLL, "tpc-doc-2")), 2000);
        final var dtxId = UUID.nameUUIDFromBytes("tpc-2".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        pool.request(serverAddress(), control(ClusterMessageType.PREPARE_TX, "tpc-2", dtxId), 2000);
        assertEquals(ClusterMessageType.ABORT_TX_ACK,
                pool.request(serverAddress(), control(ClusterMessageType.ABORT_TX, "tpc-2", dtxId), 2000).getType());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("tpc-doc-2"));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void test_tx_status_reflects_commit_decision() throws Exception {
        configureMembership(1, node("self", serverPort));
        final var dtxId = UUID.randomUUID().toString();
        assertEquals(ClusterMessageType.ABORT_TX_ACK,
                pool.request(serverAddress(), control(ClusterMessageType.TX_STATUS, null, dtxId), 2000).getType());
        org.techhouse.ops.Tx2pcLog.recordCoordinatorCommit(dtxId, List.of("127.0.0.1:1"));
        assertEquals(ClusterMessageType.COMMIT_TX_ACK,
                pool.request(serverAddress(), control(ClusterMessageType.TX_STATUS, null, dtxId), 2000).getType());
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

        // The write is forwarded to (and buffered on) the owner, which is now registered as a participant.
        assertNotNull(relayed);
        assertTrue(relayed.contains("routed"), "expected the forwarded write to be buffered, got: " + relayed);
        assertTrue(clientTracker.transactionParticipants(clientId).contains(serverAddress().toString()));

        // Teardown aborts the participant, releasing its session.
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
    public void test_local_owned_write_runs_locally() throws Exception {
        configureMembership(1, node("self", serverPort));
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var request = saveRequest(TestGlobals.COLL, "local-slice");
        // Self owns everything in a single-node ring: the write runs locally (null) and records a local slice.
        assertNull(router.forward(request, eJson.toJson(request), true, "admin", clientId));
        assertTrue(clientTracker.hasLocalSlice(clientId));
        assertTrue(clientTracker.transactionParticipants(clientId).isEmpty());
    }

    @Test
    public void test_teardown_is_noop_without_remote_participants() throws Exception {
        configureMembership(1, node("self", serverPort));
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        // No remote participants: teardown is a no-op returning false (caller does local cleanup).
        assertFalse(router.teardownTransaction(clientId));
    }

    @Test
    public void test_single_remote_commit_uses_fast_path() throws Exception {
        configureMembership(2, node("self", 19990), node("other", serverPort));
        final var coll = collectionOwnedByOther();
        createCollection(coll);
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var save = saveRequest(coll, "fastpath");
        router.forward(save, eJson.toJson(save), true, "admin", clientId);
        final var commit = new CommitTransactionRequest();
        final var relayed = router.forward(commit, eJson.toJson(commit), true, "admin", clientId);
        assertNotNull(relayed);
        assertTrue(relayed.contains("committed"), "expected committed response, got: " + relayed);
        final var find = new FindByIdRequest(TestGlobals.DB, coll);
        find.set_id("fastpath");
        assertEquals(OperationStatus.OK, processor.processMessage(find).getStatus());
        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_transaction_read_forwarded_to_participant() throws Exception {
        configureMembership(2, node("self", 19990), node("other", serverPort));
        final var coll = collectionOwnedByOther();
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var save = saveRequest(coll, "read-yw");
        router.forward(save, eJson.toJson(save), true, "admin", clientId);
        final var read = new FindByIdRequest(TestGlobals.DB, coll);
        read.set_id("read-yw");
        final var relayed = router.forward(read, eJson.toJson(read), true, "admin", clientId);
        assertNotNull(relayed);
        assertTrue(relayed.contains("read-yw"), "expected read-your-writes result, got: " + relayed);
        router.teardownTransaction(clientId);
    }

    @Test
    public void test_rollback_routed_to_participant() throws Exception {
        configureMembership(2, node("self", 19990), node("other", serverPort));
        final var coll = collectionOwnedByOther();
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        final var save = saveRequest(coll, "rb-routed");
        router.forward(save, eJson.toJson(save), true, "admin", clientId);
        final var rollback = new RollbackTransactionRequest();
        final var relayed = router.forward(rollback, eJson.toJson(rollback), true, "admin", clientId);
        assertNotNull(relayed);
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    // ---------- owner-side durable resolution & no-session paths ----------

    @Test
    public void test_prepare_tx_for_unknown_session_votes_no() throws Exception {
        final var response = pool.request(serverAddress(),
                control(ClusterMessageType.PREPARE_TX, "no-such-session", UUID.randomUUID().toString()), 2000);
        assertEquals(ClusterMessageType.ERROR, response.getType());
    }

    @Test
    public void test_commit_tx_resolves_durable_slice_when_session_absent() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("durable-commit"));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 0,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, obj));
        Tx2pcLog.recordParticipantPrepared(dtxId, serverAddress().toString(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));

        final var response = pool.request(serverAddress(), control(ClusterMessageType.COMMIT_TX, "gone-session", dtxId),
                2000);
        assertEquals(ClusterMessageType.COMMIT_TX_ACK, response.getType());
        assertEquals(OperationStatus.OK, findStatus("durable-commit"));
    }

    @Test
    public void test_abort_tx_resolves_durable_slice_when_session_absent() throws Exception {
        final var dtxId = UUID.randomUUID().toString();
        final var obj = new JsonObject();
        obj.add("_id", new JsonString("durable-abort"));
        AdminOperationHelper.saveTransactionOp(new AdminTransactionEntry(dtxId, "client", 0,
                AdminTransactionEntry.OP_TYPE_SAVE, TestGlobals.DB, TestGlobals.COLL, obj));
        Tx2pcLog.recordParticipantPrepared(dtxId, serverAddress().toString(),
                List.of(Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL)));

        final var response = pool.request(serverAddress(), control(ClusterMessageType.ABORT_TX, "gone-session", dtxId),
                2000);
        assertEquals(ClusterMessageType.ABORT_TX_ACK, response.getType());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("durable-abort"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }
}
