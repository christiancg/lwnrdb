package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.membership.MembershipService;
import org.techhouse.cluster.msg.ClusterMessage;
import org.techhouse.cluster.msg.ClusterMessageType;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AbandonedTxSessionTest {
    private static final String SESSION_ID = "session-under-test";
    private static final String EDGE_NODE_ID = "edge";

    private final Tx2pcRecovery recovery = IocContainer.get(Tx2pcRecovery.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private ClusterConfig realConfig;
    private MembershipService realMembership;
    private PeerConnectionPool realPool;

    private static NodeInfo node(String id, int port) {
        return new NodeInfo(id, "127.0.0.1", port, NodeState.ALIVE, 1L, 1L);
    }

    private void coordinatorAnswers(String status) throws Exception {
        final var reply = new ClusterMessage();
        reply.setType(ClusterMessageType.TX_STATUS_ACK);
        reply.setTxStatus(status);
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong())).thenReturn(reply);
        TestUtils.setPrivateField(recovery, "pool", pool);
    }

    private void buffersAWriteOnItsOwnThread() throws Exception {
        final var session = clientTracker.registerTxSession(SESSION_ID, "admin", EDGE_NODE_ID);
        final var clientId = session.clientId();
        session.submit(() -> {
            TransactionOperationHelper.start(clientId, UUID.randomUUID(), 0);
            final var object = new JsonObject();
            object.addProperty("_id", "buffered");
            object.addProperty("v", 1);
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            request.setObject(object);
            request.set_id("buffered");
            return TransactionOperationHelper.bufferSave(request, clientTracker.getActiveTransaction(clientId));
        }).get();
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        realConfig = TestUtils.getPrivateField(recovery, "clusterConfig", ClusterConfig.class);
        realMembership = TestUtils.getPrivateField(recovery, "membershipService", MembershipService.class);
        realPool = TestUtils.getPrivateField(recovery, "pool", PeerConnectionPool.class);
        final var config = mock(ClusterConfig.class);
        when(config.isEnabled()).thenReturn(true);
        // Zero, so the sweep inspects the session on its first pass rather than after a real idle window.
        when(config.deadTimeoutMs()).thenReturn(0L);
        when(config.replicationAckTimeoutMs()).thenReturn(200L);
        TestUtils.setPrivateField(recovery, "clusterConfig", config);
        final var membership = mock(MembershipService.class);
        when(membership.membershipView())
                .thenReturn(new MembershipView(List.of(node("self", 5000), node(EDGE_NODE_ID, 5001))));
        TestUtils.setPrivateField(recovery, "membershipService", membership);
    }

    @AfterEach
    public void tearDown() throws Exception {
        clientTracker.removeTxSession(SESSION_ID);
        TestUtils.setPrivateField(recovery, "clusterConfig", realConfig);
        TestUtils.setPrivateField(recovery, "membershipService", realMembership);
        TestUtils.setPrivateField(recovery, "pool", realPool);
        TestUtils.releaseAllLocks();
        TestUtils.resetClients();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_buffered_slice_takes_the_collection_write_lock() throws Exception {
        buffersAWriteOnItsOwnThread();

        assertFalse(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_slice_whose_coordinator_has_no_record_is_rolled_back_and_its_lock_released() throws Exception {
        buffersAWriteOnItsOwnThread();
        coordinatorAnswers("NO_RECORD");

        recovery.reapAbandonedSessions();

        assertNull(clientTracker.txSession(SESSION_ID));
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
        locks.release(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_a_slice_whose_coordinator_aborted_is_rolled_back() throws Exception {
        buffersAWriteOnItsOwnThread();
        coordinatorAnswers("ABORTED");

        recovery.reapAbandonedSessions();

        assertNull(clientTracker.txSession(SESSION_ID));
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
        locks.release(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_a_slice_whose_coordinator_still_holds_the_transaction_is_left_alone() throws Exception {
        buffersAWriteOnItsOwnThread();
        coordinatorAnswers("UNKNOWN");

        recovery.reapAbandonedSessions();

        assertNotNull(clientTracker.txSession(SESSION_ID));
        assertFalse(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_a_slice_whose_coordinator_cannot_be_reached_is_left_alone() throws Exception {
        buffersAWriteOnItsOwnThread();
        final var pool = mock(PeerConnectionPool.class);
        when(pool.request(any(), any(), anyLong()))
                .thenThrow(new PeerUnreachableException("down", new java.io.IOException("down")));
        TestUtils.setPrivateField(recovery, "pool", pool);

        recovery.reapAbandonedSessions();

        assertNotNull(clientTracker.txSession(SESSION_ID));
        assertFalse(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
    }

    @Test
    public void test_the_buffered_write_is_not_visible_after_the_reap() throws Exception {
        buffersAWriteOnItsOwnThread();
        coordinatorAnswers("NO_RECORD");

        recovery.reapAbandonedSessions();

        final var response = TransactionOperationHelper.start(UUID.randomUUID());
        assertEquals(OperationStatus.OK, response.getStatus());
    }
}
