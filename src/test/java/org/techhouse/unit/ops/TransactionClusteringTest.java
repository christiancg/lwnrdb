package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.Socket;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ClusterCoordinator;
import org.techhouse.cluster.MembershipView;
import org.techhouse.cluster.NodeInfo;
import org.techhouse.cluster.NodeState;
import org.techhouse.cluster.ReplicationOutcome;
import org.techhouse.cluster.Replicator;
import org.techhouse.cluster.TransactionSessionReaper;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.ownership.OwnershipManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.ops.req.CommitTransactionRequest;
import org.techhouse.ops.req.FindByIdRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.req.StartTransactionRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionClusteringTest {
    private final Configuration config = Configuration.getInstance();
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final MembershipService membershipService = IocContainer.get(MembershipService.class);
    private final OwnershipManager ownership = IocContainer.get(OwnershipManager.class);
    private final ClusterCoordinator coordinator = IocContainer.get(ClusterCoordinator.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private boolean origEnabled;
    private int origExpected;
    private Replicator origReplicator;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.resetClients();
        origEnabled = config.isClusterEnabled();
        origExpected = config.getClusterExpectedSize();
        origReplicator = TestUtils.getPrivateField(coordinator, "replicator", Replicator.class);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "clusterEnabled", origEnabled);
        TestUtils.setPrivateField(config, "clusterExpectedSize", origExpected);
        TestUtils.setPrivateField(coordinator, "replicator", origReplicator);
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

    private SaveRequest saveRequest(String id) {
        final var req = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        final var obj = new JsonObject();
        obj.add("_id", new JsonString(id));
        req.setObject(obj);
        req.set_id(id);
        return req;
    }

    private UUID newClient() {
        final var socket = mock(Socket.class);
        final var addr = mock(InetAddress.class);
        when(socket.getInetAddress()).thenReturn(addr);
        when(addr.getHostAddress()).thenReturn("127.0.0.1");
        return clientTracker.addClient(socket);
    }

    private OperationStatus findStatus(String id) {
        final var request = new FindByIdRequest(TestGlobals.DB, TestGlobals.COLL);
        request.set_id(id);
        return processor.processMessage(request).getStatus();
    }

    private UUID startedClientWithWrite(String id) {
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest(id), clientId);
        return clientId;
    }

    @Test
    public void test_prepare_then_commit_prepared_persists() throws Exception {
        configureMembership(1, node("self", 5000));
        final var clientId = startedClientWithWrite("prep-commit");
        final var dtxId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        assertTrue(TransactionOperationHelper.prepare(clientId, "127.0.0.1:5000"));
        assertTrue(Tx2pcLog.isPrepared(dtxId));
        assertEquals(OperationStatus.OK, TransactionOperationHelper.commitPrepared(clientId).getStatus());
        assertEquals(OperationStatus.OK, findStatus("prep-commit"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_prepare_without_quorum_votes_no() throws Exception {
        configureMembership(3, node("self", 5000));
        final var clientId = startedClientWithWrite("prep-nq");
        assertFalse(TransactionOperationHelper.prepare(clientId, "127.0.0.1:5000"));
    }

    @Test
    public void test_abort_discards_prepared_slice() throws Exception {
        configureMembership(1, node("self", 5000));
        final var clientId = startedClientWithWrite("prep-abort");
        final var dtxId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        assertTrue(TransactionOperationHelper.prepare(clientId, "127.0.0.1:5000"));
        TransactionOperationHelper.abort(clientId);
        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertEquals(OperationStatus.NOT_FOUND, findStatus("prep-abort"));
    }

    @Test
    public void test_single_node_commit_replicates_and_succeeds() throws Exception {
        configureMembership(1, node("self", 5000));
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("committed"), clientId);
        final var response = processor.processMessage(new CommitTransactionRequest(), clientId);
        assertEquals(OperationStatus.OK, response.getStatus());
        assertEquals(OperationStatus.OK, findStatus("committed"));
        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_commit_without_quorum_aborts() throws Exception {
        // Three expected nodes but only one alive: no write quorum.
        configureMembership(3, node("self", 5000));
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("no-quorum"), clientId);
        final var response = processor.processMessage(new CommitTransactionRequest(), clientId);
        assertEquals("503-2", response.getErrorCode());
        assertEquals(OperationStatus.NOT_FOUND, findStatus("no-quorum"));
        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_commit_replication_timeout_is_reported_but_commit_stands() throws Exception {
        configureMembership(1, node("self", 5000));
        final var replicator = mock(Replicator.class);
        when(replicator.broadcastTx(any())).thenReturn(ReplicationOutcome.TIMEOUT);
        TestUtils.setPrivateField(coordinator, "replicator", replicator);
        final var clientId = newClient();
        processor.processMessage(new StartTransactionRequest(), clientId);
        processor.processMessage(saveRequest("timed-out"), clientId);
        final var response = processor.processMessage(new CommitTransactionRequest(), clientId);
        assertEquals("503-3", response.getErrorCode());
        // The local commit stands even when replication timed out.
        assertEquals(OperationStatus.OK, findStatus("timed-out"));
    }

    @Test
    public void test_reaper_rolls_back_session_of_departed_node_and_releases_lock() throws Exception {
        configureMembership(1, node("self", 5000));
        final var session = clientTracker.registerTxSession("edge-session", "admin", "edge-1");
        // Buffer a write on the session's own executor thread so it holds the collection write lock there.
        session.submit(() -> {
            TransactionOperationHelper.start(session.clientId());
            return TransactionOperationHelper.bufferSave(saveRequest("stranded"),
                    clientTracker.getActiveTransaction(session.clientId()));
        }).get();
        assertFalse(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL), "lock should be held by the session");

        // A membership view that no longer contains the originating edge node triggers a reap.
        TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of(node("self", 5000))));

        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL), "lock should have been released");
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_reaper_keeps_session_of_live_node() throws Exception {
        configureMembership(1, node("self", 5000));
        clientTracker.registerTxSession("live-session", "admin", "edge-1");
        TransactionOperationHelper
                .reapTransactionsForDeparted(new MembershipView(List.of(node("self", 5000), node("edge-1", 5002))));
        assertNotNull(clientTracker.txSessionsSnapshot().get("live-session"));
    }

    @Test
    public void test_reaper_listener_reaps_on_membership_change() throws Exception {
        configureMembership(1, node("self", 5000));
        clientTracker.registerTxSession("listener-session", "admin", "edge-gone");
        new TransactionSessionReaper().onMembershipChanged(new MembershipView(List.of(node("self", 5000))));
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }
}
