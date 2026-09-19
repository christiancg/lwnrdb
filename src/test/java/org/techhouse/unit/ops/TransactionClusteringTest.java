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
import org.techhouse.ops.TwoPhaseParticipant;
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
    private volatile boolean origEnabled;
    private volatile int origExpected;
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
        assertTrue(TwoPhaseParticipant.prepare(clientId, "127.0.0.1:5000", java.util.List.of()));
        assertTrue(Tx2pcLog.isPrepared(dtxId));
        assertEquals(OperationStatus.OK, TwoPhaseParticipant.commitPrepared(clientId).getStatus());
        assertEquals(OperationStatus.OK, findStatus("prep-commit"));
        assertFalse(Tx2pcLog.isPrepared(dtxId));
        assertNull(clientTracker.getActiveTransaction(clientId));
    }

    @Test
    public void test_prepare_without_quorum_votes_no() throws Exception {
        configureMembership(3, node("self", 5000));
        final var clientId = startedClientWithWrite("prep-nq");
        assertFalse(TwoPhaseParticipant.prepare(clientId, "127.0.0.1:5000", java.util.List.of()));
    }

    @Test
    public void test_abort_discards_prepared_slice() throws Exception {
        configureMembership(1, node("self", 5000));
        final var clientId = startedClientWithWrite("prep-abort");
        final var dtxId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        assertTrue(TwoPhaseParticipant.prepare(clientId, "127.0.0.1:5000", java.util.List.of()));
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
    @SuppressWarnings("BusyWait")
    public void test_reaper_listener_reaps_on_membership_change() throws Exception {
        configureMembership(1, node("self", 5000));
        clientTracker.registerTxSession("listener-session", "admin", "edge-gone");

        new TransactionSessionReaper().onMembershipChanged(new MembershipView(List.of(node("self", 5000))));

        for (var i = 0; i < 100 && !clientTracker.txSessionsSnapshot().isEmpty(); i++) {
            Thread.sleep(20);
        }
        assertTrue(clientTracker.txSessionsSnapshot().isEmpty());
    }

    @Test
    public void test_commit_refuses_when_ownership_moved() throws Exception {
        configureMembership(1, node("self", 5000));
        final var clientId = startedClientWithWrite("ownership-moved");
        final var realOwnership = TestUtils.getPrivateField(coordinator, "ownershipManager", OwnershipManager.class);
        final var moved = mock(OwnershipManager.class);
        when(moved.hasQuorum()).thenReturn(true);
        when(moved.isOwner(any(), any())).thenReturn(false);
        TestUtils.setPrivateField(coordinator, "ownershipManager", moved);
        try {
            final var response = processor.processMessage(new CommitTransactionRequest(), clientId);
            assertEquals("421-1", response.getErrorCode(),
                    "a commit whose collections moved to another owner is no longer mutually exclusive with that"
                            + " owner's writers and must be refused");
        } finally {
            TestUtils.setPrivateField(coordinator, "ownershipManager", realOwnership);
        }

        assertEquals(OperationStatus.NOT_FOUND, findStatus("ownership-moved"),
                "the refusal must land before the durable commit marker, so nothing is applied");
    }

    @Test
    public void test_prepare_votes_no_for_an_aborted_transaction() throws Exception {
        configureMembership(1, node("self", 5000));
        final var clientId = startedClientWithWrite("prep-aborted");
        final var dtxId = clientTracker.getActiveTransaction(clientId).getTransactionId().toString();
        TransactionOperationHelper.abortInPlace(clientId);

        assertFalse(TwoPhaseParticipant.prepare(clientId, "127.0.0.1:5000", java.util.List.of()),
                "a participant that already aborted in place released its locks and discarded its ops, so voting"
                        + " yes would report the whole transaction committed while its slice never existed");
        assertFalse(Tx2pcLog.isPrepared(dtxId));
    }

    @Test
    public void test_commit_prepared_refuses_an_aborted_transaction() throws Exception {
        configureMembership(1, node("self", 5000));
        final var clientId = startedClientWithWrite("commit-aborted");
        TransactionOperationHelper.abortInPlace(clientId);

        final var response = TwoPhaseParticipant.commitPrepared(clientId);

        assertEquals(OperationStatus.ERROR, response.getStatus());
        assertEquals("409-9", response.getErrorCode());
    }

    @Test
    public void test_durable_resolution_goes_through_a_live_prepared_session() throws Exception {
        configureMembership(1, node("self", 5000));
        final var session = clientTracker.registerTxSession("sess-live", "alice", "edge");
        final var sessionClient = session.clientId();
        session.submit(() -> {
            processor.processMessage(new StartTransactionRequest(), sessionClient);
            processor.processMessage(saveRequest("live-sess"), sessionClient);
            return TwoPhaseParticipant.prepare(sessionClient, "127.0.0.1:5000", java.util.List.of());
        }).get(10, java.util.concurrent.TimeUnit.SECONDS);
        final var dtxId = clientTracker.getActiveTransaction(sessionClient).getTransactionId().toString();
        final var marker = Tx2pcLog.readParticipantMarker(dtxId);
        assertNotNull(marker);

        final var resolved = new java.util.concurrent.atomic.AtomicBoolean();
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final var worker = new Thread(() -> {
            try {
                TwoPhaseParticipant.commitPreparedFromDurable(dtxId, marker.collections());
                resolved.set(true);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "durable-resolve");
        worker.setDaemon(true);
        worker.start();
        worker.join(15_000L);

        assertNull(failure.get());
        assertTrue(resolved.get(),
                "the prepared session still holds the slice's write locks on its own thread, so a durable"
                        + " replay taking them from the recovery thread would wedge it for the process's life");

        assertEquals(OperationStatus.OK, findStatus("live-sess"));
        assertNull(clientTracker.getActiveTransaction(sessionClient));
    }
}
