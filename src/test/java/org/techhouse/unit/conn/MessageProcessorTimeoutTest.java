package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.TxCommitLog;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorTimeoutTest {
    private static final String ADMIN = "timeout_admin";
    private static final long SHORT_TIMEOUT_MS = 300;
    private static final String COLLECTION_ID = Cache.getCollectionIdentifier(TestGlobals.DB, TestGlobals.COLL);

    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration config = Configuration.getInstance();
    private ScriptedConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        TestUtils.setPrivateField(config, "authTimeoutMs", SHORT_TIMEOUT_MS);
        TestUtils.setPrivateField(config, "transactionIdleTimeoutMs", SHORT_TIMEOUT_MS);
        connection = new ScriptedConnection();
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.close();
        TestUtils.setPrivateField(config, "authTimeoutMs", ConfigKey.AUTH_TIMEOUT_MS.defaultValue());
        TestUtils.setPrivateField(config, "transactionIdleTimeoutMs",
                ConfigKey.TRANSACTION_IDLE_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static String authenticate() {
        return "{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"password123\"}";
    }

    private static String save(String id) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\"}}";
    }

    private static String findById(String id) {
        return "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}";
    }

    private static boolean collectionIsWritable() throws InterruptedException {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var free = new AtomicBoolean();
        final var probe = Thread.ofPlatform().start(() -> {
            free.set(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL));
            if (free.get()) {
                locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
            }
        });
        probe.join(5000);
        return free.get();
    }

    @SuppressWarnings("BusyWait")
    private static boolean eventually(Check check) throws Exception {
        final var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (check.holds()) {
                return true;
            }
            Thread.sleep(20);
        }
        return check.holds();
    }

    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }

    private void openTransactionWithOneWrite(String id) throws InterruptedException {
        connection.start();
        assertTrue(connection.exchange(authenticate()).contains("\"OK\""));
        assertTrue(connection.exchange("{\"type\":\"START_TRANSACTION\"}").contains("\"OK\""));
        assertTrue(connection.exchange(save(id)).contains("\"OK\""));
        assertFalse(collectionIsWritable());
    }

    private void openAndReap(String id) throws Exception {
        openTransactionWithOneWrite(id);
        assertTrue(eventually(MessageProcessorTimeoutTest::collectionIsWritable), "the idle transaction was reaped");
    }

    @Test
    public void test_an_unauthenticated_connection_is_answered_408_4_and_closed() throws Exception {
        connection.start();

        final var refusal = connection.response();

        assertNotNull(refusal);
        assertTrue(refusal.contains("408-4") && refusal.contains("CLOSE_CONNECTION"), refusal);
        assertTrue(connection.awaitClosed(5000));
    }

    @Test
    public void test_a_failed_authenticate_does_not_reset_the_deadline() throws Exception {
        TestUtils.setPrivateField(config, "authTimeoutMs", 1000L);
        final var started = System.nanoTime();
        connection.start();
        Thread.sleep(500);
        final var failed = connection
                .exchange("{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"wrong-password\"}");
        assertTrue(failed.contains("401-3"), failed);
        Thread.sleep(200);
        connection.send("   ");

        final var refusal = connection.response();
        final var elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(refusal.contains("408-4"), refusal);
        assertTrue(elapsedMillis < 1450, "the deadline moved: closed after " + elapsedMillis + " ms");
    }

    @Test
    public void test_an_authenticated_idle_connection_is_not_timed() throws Exception {
        connection.start();
        assertTrue(connection.exchange(authenticate()).contains("\"OK\""));

        assertNull(connection.responseWithin(SHORT_TIMEOUT_MS * 3));

        assertEquals(0, connection.timeouts().getLast());
        assertTrue(connection.exchange("{\"type\":\"LIST_DATABASES\"}").contains("\"OK\""));
    }

    @Test
    public void test_a_listen_connection_is_not_timed() throws Exception {
        connection.start();
        assertTrue(connection.exchange(authenticate()).contains("\"OK\""));
        final var listen = connection.exchange("{\"type\":\"LISTEN\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"aggregationSteps\":[]}");
        assertTrue(listen.contains("listenId"), listen);

        Thread.sleep(SHORT_TIMEOUT_MS * 3);

        assertEquals(0, connection.timeouts().getLast());
        assertEquals(0, connection.timeoutsFired());
        assertFalse(connection.awaitClosed(10));
    }

    @Test
    public void test_an_idle_transaction_is_rolled_back_on_the_connection_thread() throws Exception {
        final var locks = IocContainer.get(ResourceLocking.class);
        final var heldByReaderThread = new AtomicBoolean();
        connection.onTimeout(() -> heldByReaderThread.set(locks.isWriteLockedByCurrentThread(COLLECTION_ID)));

        openAndReap("idle-1");

        assertTrue(heldByReaderThread.get(), "the timeout fired on the thread that owns the write lock");
        assertTrue(eventually(() -> clientTracker.getActiveTransaction(connection.clientId()) == null));
    }

    @Test
    public void test_the_next_request_after_a_reap_is_answered_409_16_and_not_executed() throws Exception {
        openAndReap("idle-2");

        final var refused = connection.exchange(save("standalone"));

        assertTrue(refused.contains("409-16") && refused.contains("\"SAVE\""), refused);
        assertTrue(connection.exchange(findById("standalone")).contains("NOT_FOUND"));
        assertTrue(connection.exchange(findById("idle-2")).contains("NOT_FOUND"));
    }

    @Test
    public void test_commit_and_rollback_after_a_reap_answer_409_16() throws Exception {
        openAndReap("idle-3");
        final var commit = connection.exchange("{\"type\":\"COMMIT_TRANSACTION\"}");
        assertTrue(commit.contains("409-16"), commit);

        assertTrue(connection.exchange("{\"type\":\"START_TRANSACTION\"}").contains("\"OK\""));
        assertTrue(connection.exchange(save("idle-4")).contains("\"OK\""));
        assertTrue(eventually(MessageProcessorTimeoutTest::collectionIsWritable));
        final var rollback = connection.exchange("{\"type\":\"ROLLBACK_TRANSACTION\"}");

        assertTrue(rollback.contains("409-16"), rollback);
    }

    @Test
    public void test_close_connection_after_a_reap_is_not_refused() throws Exception {
        openAndReap("idle-5");

        final var bye = connection.exchange("{\"type\":\"CLOSE_CONNECTION\"}");

        assertTrue(bye.contains("CLOSE_CONNECTION") && !bye.contains("409-16"), bye);
        assertTrue(connection.awaitClosed(5000));
    }

    @Test
    public void test_only_one_request_is_refused_after_a_reap() throws Exception {
        openAndReap("idle-6");

        assertTrue(connection.exchange("{\"type\":\"LIST_DATABASES\"}").contains("409-16"));
        assertTrue(connection.exchange(save("after")).contains("\"OK\""));
        assertTrue(connection.exchange(findById("after")).contains("\"OK\""));
    }

    @Test
    public void test_the_connection_stays_open_after_a_reap() throws Exception {
        openAndReap("idle-7");

        assertFalse(connection.awaitClosed(SHORT_TIMEOUT_MS * 2));
        assertEquals(0, connection.timeouts().getLast());
        assertEquals(1, connection.timeoutsFired());
    }

    @Test
    public void test_a_fenced_transaction_is_not_reaped_and_is_not_timed_again() throws Exception {
        openTransactionWithOneWrite("fenced");
        final var transaction = clientTracker.getActiveTransaction(connection.clientId());
        final var txId = transaction.getTransactionId().toString();
        TxCommitLog.recordLocalCommit(txId, transaction.getBufferedOpIds(), List.of(COLLECTION_ID));
        try {
            assertTrue(eventually(() -> connection.timeouts().getLast() == 0));
            Thread.sleep(SHORT_TIMEOUT_MS * 2);

            assertTrue(connection.timeoutsFired() <= 1);
            assertFalse(collectionIsWritable());
            assertSame(transaction, clientTracker.getActiveTransaction(connection.clientId()));
            assertNull(clientTracker.consumeTransactionReaped(connection.clientId()));
        } finally {
            TxCommitLog.clearLocalCommit(txId);
        }
    }

    @Test
    public void test_a_failed_reap_is_not_retried() throws Exception {
        openTransactionWithOneWrite("stranded");
        final var transaction = clientTracker.getActiveTransaction(connection.clientId());
        final var strandedLock = TestGlobals.DB + "|stranded";
        final var locks = IocContainer.get(ResourceLocking.class);
        locks.lockWrite(strandedLock);
        transaction.getHeldLocks().add(strandedLock);
        try {
            assertTrue(eventually(MessageProcessorTimeoutTest::collectionIsWritable));
            assertTrue(eventually(() -> connection.timeouts().getLast() == 0));
            Thread.sleep(SHORT_TIMEOUT_MS * 2);

            assertEquals(1, connection.timeoutsFired());
            assertSame(transaction, clientTracker.getActiveTransaction(connection.clientId()));
            assertNull(clientTracker.consumeTransactionReaped(connection.clientId()));
        } finally {
            locks.releaseWrite(strandedLock);
        }
    }

    @Test
    public void test_an_aborted_transaction_is_not_reaped() throws Exception {
        connection.start();
        assertTrue(connection.exchange(authenticate()).contains("\"OK\""));
        assertTrue(connection.exchange("{\"type\":\"START_TRANSACTION\"}").contains("\"OK\""));
        final var transaction = clientTracker.getActiveTransaction(connection.clientId());
        transaction.markAborted();

        assertTrue(eventually(() -> connection.timeouts().getLast() == 0));
        Thread.sleep(SHORT_TIMEOUT_MS * 2);

        assertSame(transaction, clientTracker.getActiveTransaction(connection.clientId()));
        final var rollback = connection.exchange("{\"type\":\"ROLLBACK_TRANSACTION\"}");
        assertFalse(rollback.contains("409-16"), rollback);
    }

    @Test
    public void test_shutdown_during_idle_wait_still_cleans_up_through_the_disconnect_path() throws Exception {
        TestUtils.setPrivateField(config, "transactionIdleTimeoutMs", 60_000L);
        openTransactionWithOneWrite("shutdown");

        final var departure = clientTracker.signalDisconnect(connection.clientId());

        assertNotNull(departure);
        assertTrue(departure.await(5, TimeUnit.SECONDS));
        assertTrue(collectionIsWritable());
        assertEquals(0, connection.timeoutsFired());
    }
}
