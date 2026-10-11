package org.techhouse.unit.cluster;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.techhouse.test.Tx2pcFixtures.SELF_ADDRESS;
import static org.techhouse.test.Tx2pcFixtures.findStatus;
import static org.techhouse.test.Tx2pcFixtures.seedPreparedSlice;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.Tx2pcRecovery;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.TxSession;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.TransactionOperationHelper;
import org.techhouse.ops.TwoPhaseParticipant;
import org.techhouse.ops.Tx2pcLog;
import org.techhouse.test.ClusterTestHarness;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class Tx2pcRecoveryResilienceTest {
    private static final String BUSY_DOC = "busy-doc";
    private final Configuration config = Configuration.getInstance();
    private final Tx2pcRecovery recovery = IocContainer.get(Tx2pcRecovery.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final ClusterTestHarness cluster = new ClusterTestHarness();
    private volatile long origAckTimeoutMs;

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        origAckTimeoutMs = config.getReplicationAckTimeoutMs();
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", 200L);
        cluster.enableAlone();
    }

    @AfterEach
    public void tearDown() throws Exception {
        TestUtils.setPrivateField(config, "replicationAckTimeoutMs", origAckTimeoutMs);
        cluster.disable();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void runSweep() throws Exception {
        final var sweep = Tx2pcRecovery.class.getDeclaredMethod("sweep");
        sweep.setAccessible(true);
        sweep.invoke(recovery);
    }

    @Test
    public void test_an_error_from_a_slice_does_not_escape_the_round() throws Exception {
        final var dtxId = "44444444-4444-4444-4444-444444444444";
        seedPreparedSlice(dtxId, "err-a");
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of(SELF_ADDRESS));

        try (var mocked = mockStatic(TwoPhaseParticipant.class)) {
            mocked.when(() -> TwoPhaseParticipant.resolveFromDurable(anyString(), anyBoolean(), anyLong()))
                    .thenThrow(new StackOverflowError("script blew the stack"));

            assertDoesNotThrow(recovery::recover,
                    "an Error from a script body is an expected outcome of replaying a slice");
        }

        assertTrue(Tx2pcLog.isPrepared(dtxId), "the slice is left for the next round rather than lost");
    }

    @Test
    public void test_the_sweep_survives_an_error_and_runs_again() throws Exception {
        final var dtxId = "66666666-6666-6666-6666-666666666666";
        seedPreparedSlice(dtxId, "sweep-doc");
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of(SELF_ADDRESS));

        try (var mocked = mockStatic(TwoPhaseParticipant.class)) {
            mocked.when(() -> TwoPhaseParticipant.resolveFromDurable(anyString(), any(Boolean.class), anyLong()))
                    .thenThrow(new OutOfMemoryError("budget"));
            assertDoesNotThrow(this::runSweep);
            assertDoesNotThrow(this::runSweep);
        }

        assertTrue(Tx2pcLog.isCommitted(dtxId), "the slice stays for the next round rather than being lost");
    }

    @Test
    public void test_coordinator_recovery_skips_a_slice_whose_collection_is_busy() throws Exception {
        final var dtxId = "77777777-7777-7777-7777-777777777777";
        seedPreparedSlice(dtxId, BUSY_DOC);
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of(SELF_ADDRESS));
        final var holding = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var releasedOnSignal = new AtomicBoolean();
        final var holder = new Thread(() -> {
            try {
                locks.lock(TestGlobals.DB, TestGlobals.COLL);
                holding.countDown();
                releasedOnSignal.set(release.await(5, TimeUnit.SECONDS));
                locks.release(TestGlobals.DB, TestGlobals.COLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));

        final var started = System.currentTimeMillis();
        assertDoesNotThrow(recovery::recover);
        final var elapsed = System.currentTimeMillis() - started;

        release.countDown();
        holder.join(5000);
        assertTrue(releasedOnSignal.get(), "the lock holder released on the signal, not on its own timeout");
        assertTrue(elapsed < 5000, "the sweep must give up on the budget instead of parking on the lock");
        assertTrue(Tx2pcLog.isCommitted(dtxId), "a skipped slice is retried next round, not discarded");
        assertNotSame(OperationStatus.OK, findStatus(BUSY_DOC),
                "the document the skipped slice would have written is not there yet");
    }
    @Test
    public void test_coordinator_recovery_resolves_when_the_lock_is_free() throws Exception {
        final var dtxId = "88888888-8888-8888-8888-888888888888";
        seedPreparedSlice(dtxId, "free-doc");
        Tx2pcLog.recordCoordinatorCommit(dtxId, null, List.of(SELF_ADDRESS));

        recovery.recover();

        assertEquals(OperationStatus.OK, findStatus("free-doc"),
                "the budget only bounds the wait; an uncontended lock still applies the slice");
        assertFalse(Tx2pcLog.isCommitted(dtxId));
    }

    @Test
    public void test_startup_resolution_still_waits_without_a_budget() throws Exception {
        final var dtxId = "99999999-9999-9999-9999-999999999999";
        seedPreparedSlice(dtxId, "startup-doc");

        TwoPhaseParticipant.resolveFromDurable(dtxId, true, 0L);

        assertEquals(OperationStatus.OK, findStatus("startup-doc"),
                "startup recovery and a peer handler still pass no budget and wait for the lock");
    }

    private record StuckSession(String sessionId, TxSession session, String dtxId, CountDownLatch release) {
    }

    private StuckSession openStuckSession(String sessionId) throws Exception {
        final var session = clientTracker.registerTxSession(sessionId, "tester", "edge-node");
        session.submit(() -> TransactionOperationHelper.start(session.clientId())).get();
        final var dtxId = clientTracker.getActiveTransaction(session.clientId()).getTransactionId().toString();
        final var release = new CountDownLatch(1);
        session.submit(() -> release.await(10, TimeUnit.SECONDS));
        return new StuckSession(sessionId, session, dtxId, release);
    }

    private void unstick(StuckSession stuck) throws Exception {
        stuck.release().countDown();
        stuck.session().submit(() -> null).get(10, TimeUnit.SECONDS);
        clientTracker.removeTxSession(stuck.sessionId());
    }

    @Test
    public void test_commit_through_a_stuck_live_session_times_out_rather_than_blocking() throws Exception {
        final var stuck = openStuckSession("stuck-commit");
        try {
            final var started = System.currentTimeMillis();
            assertThrows(TimeoutException.class,
                    () -> TwoPhaseParticipant.resolveFromDurable(stuck.dtxId(), true, 200L));
            assertTrue(System.currentTimeMillis() - started < 5000, "the budget bounds the wait on the session");
        } finally {
            unstick(stuck);
        }
    }

    @Test
    public void test_abort_through_a_stuck_live_session_honours_an_explicit_timeout() throws Exception {
        final var stuck = openStuckSession("stuck-abort");
        try {
            final var started = System.currentTimeMillis();
            assertThrows(TimeoutException.class,
                    () -> TwoPhaseParticipant.resolveFromDurable(stuck.dtxId(), false, 200L));
            assertTrue(System.currentTimeMillis() - started < 5000, "the budget bounds the wait on the session");
        } finally {
            unstick(stuck);
        }
    }

    @Test
    public void test_recovery_skips_a_round_when_the_live_session_times_out() throws Exception {
        final var stuck = openStuckSession("stuck-sweep");
        try {
            seedPreparedSlice(stuck.dtxId(), "stuck-doc");
            Tx2pcLog.recordCoordinatorCommit(stuck.dtxId(), null, List.of(SELF_ADDRESS));

            final var started = System.currentTimeMillis();
            assertDoesNotThrow(recovery::recover);

            assertTrue(System.currentTimeMillis() - started < 5000,
                    "a stuck session thread must not wedge the single recovery sweeper");
            assertTrue(Tx2pcLog.isPrepared(stuck.dtxId()), "a skipped slice is retried next round, not discarded");
            assertTrue(Tx2pcLog.isCommitted(stuck.dtxId()), "the coordinator marker is kept for the next round");
        } finally {
            unstick(stuck);
        }
    }
}
