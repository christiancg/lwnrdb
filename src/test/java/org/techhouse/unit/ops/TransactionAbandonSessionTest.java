package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.MembershipView;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.ops.resp.OperationResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TransactionAbandonSessionTest {
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private String openBufferedSession(String sessionId, String id) throws Exception {
        final var session = clientTracker.registerTxSession(sessionId, "tester", "edge-node");
        session.submit(() -> {
            TransactionOperationHelper.start(session.clientId());
            final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
            final var object = new JsonObject();
            object.add("_id", new JsonString(id));
            request.setObject(object);
            return processor.processMessage(request, session.clientId());
        }).get();
        return transactionIdOf(session.clientId());
    }

    private String transactionIdOf(UUID clientId) {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        assertNotNull(transaction);
        return transaction.getTransactionId().toString();
    }

    @Test
    public void test_abandon_session_keeps_a_fenced_transaction_registered() throws Exception {
        final var sessionId = "fenced-session";
        final var txId = openBufferedSession(sessionId, "fenced-doc");
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of());
        try {
            TransactionOperationHelper.abandonSession(sessionId);

            assertNotNull(clientTracker.txSession(sessionId),
                    "the session is the only handle naming the write locks a fenced commit deliberately kept");
            assertFalse(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL),
                    "the collection stays locked for recovery to finish the slice");
        } finally {
            TxCommitLog.clearLocalCommit(txId);
            final var session = clientTracker.txSession(sessionId);
            if (session != null) {
                session.submit(() -> TransactionOperationHelper.rollback(session.clientId())).get();
                clientTracker.removeTxSession(sessionId);
            }
        }
    }

    @Test
    public void test_abandon_session_removes_an_unfenced_session() throws Exception {
        final var sessionId = "plain-session";
        openBufferedSession(sessionId, "plain-doc");

        TransactionOperationHelper.abandonSession(sessionId);

        assertNull(clientTracker.txSession(sessionId));
        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
    }

    @Test
    public void test_abandon_session_on_an_unknown_id_is_a_no_op() {
        TransactionOperationHelper.abandonSession("no-such-session");
        assertNull(clientTracker.txSession("no-such-session"));
    }

    @Test
    public void test_is_fenced_conjoins_both_markers() throws Exception {
        final var txId = UUID.randomUUID().toString();
        assertFalse(TransactionOperationHelper.isFenced(txId));
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of());
        try {
            assertTrue(TransactionOperationHelper.isFenced(txId),
                    "a single-owner forwarded transaction fences through the commit log and never prepares");
        } finally {
            TxCommitLog.clearLocalCommit(txId);
        }
    }

    @Test
    public void test_released_its_locks_reads_the_half_applied_code() {
        assertTrue(TransactionOperationHelper.releasedItsLocks(null));
        assertTrue(TransactionOperationHelper
                .releasedItsLocks(OperationResponse.ok(OperationType.ROLLBACK_TRANSACTION, "done")));
        assertFalse(TransactionOperationHelper.releasedItsLocks(
                new OperationResponse(OperationType.COMMIT_TRANSACTION, ErrorCode.TRANSACTION_HALF_APPLIED)));
    }
    @Test
    public void test_abandon_session_keeps_the_session_when_the_rollback_outcome_is_unknown() throws Exception {
        final var sessionId = "slow-session";
        openBufferedSession(sessionId, "slow-doc");
        final var session = clientTracker.txSession(sessionId);
        final var blocking = new CountDownLatch(1);
        final var signalled = new AtomicBoolean();
        session.submit(() -> {
            signalled.set(blocking.await(3, TimeUnit.SECONDS));
            return null;
        });
        TestUtils.setPrivateField(Configuration.getInstance(), "shutdownTimeoutMs", 50L);
        try {
            TransactionOperationHelper.abandonSession(sessionId);

            assertNotNull(clientTracker.txSession(sessionId), "an outcome the reaper never saw is not a released lock");
        } finally {
            blocking.countDown();
            TestUtils.setPrivateField(Configuration.getInstance(), "shutdownTimeoutMs", 15000L);
            assertTrue(signalled.get() || clientTracker.txSession(sessionId) != null);
            final var live = clientTracker.txSession(sessionId);
            if (live != null) {
                live.submit(() -> TransactionOperationHelper.rollback(live.clientId())).get();
                clientTracker.removeTxSession(sessionId);
            }
        }
    }

    @Test
    public void test_reap_for_departed_skips_a_locally_committed_transaction() throws Exception {
        final var sessionId = "departed-session";
        final var txId = openBufferedSession(sessionId, "departed-doc");
        TxCommitLog.recordLocalCommit(txId, List.of(), List.of());
        try {
            TransactionOperationHelper.reapTransactionsForDeparted(new MembershipView(List.of()));

            assertNotNull(clientTracker.txSession(sessionId),
                    "a transaction fenced by the commit log alone never prepares, so the 2PC marker cannot see it");
        } finally {
            TxCommitLog.clearLocalCommit(txId);
            final var session = clientTracker.txSession(sessionId);
            if (session != null) {
                session.submit(() -> TransactionOperationHelper.rollback(session.clientId())).get();
                clientTracker.removeTxSession(sessionId);
            }
        }
    }
    @Test
    public void test_a_startup_replay_error_does_not_abort_startup() {
        try (var mocked = mockStatic(TxCommitLog.class)) {
            mocked.when(TxCommitLog::localCommitTxIds).thenReturn(List.of("interrupted-tx"));
            mocked.when(() -> TxCommitLog.readLocalCommitMarker("interrupted-tx"))
                    .thenThrow(new StackOverflowError("script blew the stack"));

            assertDoesNotThrow(TransactionOperationHelper::cleanupOrphansAtStartup,
                    "an Error replaying one interrupted commit must not stop the node coming up");
        }
    }
}
