package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.conn.OutboundQueue;
import org.techhouse.conn.WriteStallWatchdog;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorOutboundTest {
    private static final String ADMIN = "outbound_admin";
    private static final Pattern LISTEN_ID = Pattern.compile("\"listenId\":\"([0-9a-f-]{36})\"");
    private static final int PIPELINED_REQUESTS = 60;
    private static final String LARGE_AGGREGATE = "{\"type\":\"AGGREGATE\",\"databaseName\":\"" + TestGlobals.DB
            + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"aggregationSteps\":[]}";
    private final Configuration config = Configuration.getInstance();
    private ScriptedConnection connection;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        TestUtils.setPrivateField(config, "transactionIdleTimeoutMs", 300L);
        TestUtils.setPrivateField(config, "clientOutboundQueueMaxBytes", "1Kb");
        connection = new ScriptedConnection();
        connection.start();
        assertTrue(connection
                .exchange("{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN + "\",\"password\":\"password123\"}")
                .contains("\"OK\""));
        assertTrue(connection.exchange("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"object\":{\"_id\":\"bulky\",\"blob\":\""
                + "b".repeat(Globals.INLINE_FRAME_MAX_BYTES) + "\"}}").contains("\"OK\""));
    }

    @AfterEach
    void tearDown() throws Exception {
        connection.resumeResponses();
        connection.close();
        TestUtils.setPrivateField(config, "transactionIdleTimeoutMs",
                ConfigKey.TRANSACTION_IDLE_TIMEOUT_MS.defaultValue());
        TestUtils.setPrivateField(config, "clientOutboundQueueMaxBytes",
                ConfigKey.CLIENT_OUTBOUND_QUEUE_MAX_BYTES.defaultValue());
        TestUtils.setPrivateField(config, "clientWriteStallTimeoutMs",
                ConfigKey.CLIENT_WRITE_STALL_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
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

    private void openTransactionWithOneWrite() throws InterruptedException {
        assertTrue(connection.exchange("{\"type\":\"START_TRANSACTION\"}").contains("\"OK\""));
        assertTrue(connection.exchange("{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"object\":{\"_id\":\"held\"}}")
                .contains("\"OK\""));
        assertFalse(collectionIsWritable());
    }

    private void stopReadingAndPipelineRequests() throws InterruptedException {
        connection.stallResponses();
        connection.send(LARGE_AGGREGATE);
        assertTrue(connection.awaitBlockedWriter());
        for (var i = 0; i < PIPELINED_REQUESTS; i++) {
            connection.send(LARGE_AGGREGATE);
        }
    }

    private OutboundQueue outbound() throws ReflectiveOperationException {
        return TestUtils.getPrivateField(connection.processor(), "outbound", OutboundQueue.class);
    }

    @Test
    public void test_an_idle_transaction_is_reaped_while_a_large_response_is_unread() throws Exception {
        openTransactionWithOneWrite();
        connection.stallResponses();
        connection.send(LARGE_AGGREGATE);
        assertTrue(connection.awaitBlockedWriter());

        assertTrue(eventually(MessageProcessorOutboundTest::collectionIsWritable),
                "a drainer writes the large response, so the connection thread reaps the transaction");
        assertTrue(outbound().isOpen(), "a single unread response is no overflow");
    }

    @Test
    public void test_a_pipelining_client_keeps_the_connection_thread_free_to_reap() throws Exception {
        openTransactionWithOneWrite();
        connection.stallResponses();
        connection.send("{\"type\":\"LIST_DATABASES\"}\n{\"type\":\"LIST_DATABASES\"}");
        assertTrue(connection.awaitBlockedWriter());

        assertTrue(eventually(MessageProcessorOutboundTest::collectionIsWritable),
                "a burst of requests marks the connection as pipelining, so a drainer writes its small responses");
        assertTrue(outbound().isOpen());
        connection.resumeResponses();
        assertTrue(connection.response().contains("LIST_DATABASES"));
        assertTrue(connection.response().contains("LIST_DATABASES"));
    }

    @Test
    public void test_a_small_unread_response_holds_its_transaction_until_the_stall_timeout_closes_the_connection()
            throws Exception {
        TestUtils.setPrivateField(config, "clientWriteStallTimeoutMs", 1000L);
        openTransactionWithOneWrite();
        connection.stallResponses();
        connection.send("{\"type\":\"LIST_DATABASES\"}");
        assertTrue(connection.awaitBlockedWriter());

        Thread.sleep(600);
        assertFalse(collectionIsWritable(), "the connection thread itself is writing the small response");
        final var watchdog = IocContainer.get(WriteStallWatchdog.class);
        assertEquals(0, watchdog.scan(System.nanoTime()));
        assertEquals(1, watchdog.scan(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500)));

        assertTrue(connection.awaitClosed(5000), "the stalled write failed once the watchdog closed the socket");
        assertTrue(collectionIsWritable(), "the disconnect rolled the transaction back on its own thread");
    }

    @Test
    public void test_an_overflow_rolls_back_the_transaction_and_closes_the_connection() throws Exception {
        TestUtils.setPrivateField(config, "transactionIdleTimeoutMs",
                ConfigKey.TRANSACTION_IDLE_TIMEOUT_MS.defaultValue());
        openTransactionWithOneWrite();
        stopReadingAndPipelineRequests();

        assertTrue(eventually(() -> !outbound().isOpen()), "the backlog passed clientOutboundQueueMaxBytes");
        assertTrue(connection.awaitClosed(5000), "the connection thread ends once the socket is closed");
        assertTrue(collectionIsWritable(), "the transaction was rolled back on its own thread");
    }

    @Test
    public void test_an_overflowing_outbound_queue_ends_listens_and_closes_the_connection() throws Exception {
        final var listenResponse = connection.exchange("{\"type\":\"LISTEN\",\"databaseName\":\"" + TestGlobals.DB
                + "\",\"collectionName\":\"" + TestGlobals.COLL + "\",\"aggregationSteps\":[]}");
        final var matcher = LISTEN_ID.matcher(listenResponse);
        assertTrue(matcher.find(), listenResponse);
        final var listenId = UUID.fromString(matcher.group(1));
        final var listenManager = IocContainer.get(ListenManager.class);
        assertNotNull(listenManager.getRegistration(listenId));

        stopReadingAndPipelineRequests();

        assertTrue(eventually(() -> listenManager.getRegistration(listenId) == null),
                "the overflow ended the connection's listens");
        assertTrue(connection.awaitClosed(5000));
    }

    @Test
    public void test_a_reader_that_catches_up_is_told_why_it_was_closed() throws Exception {
        stopReadingAndPipelineRequests();
        assertTrue(eventually(() -> !outbound().isOpen()));

        connection.resumeResponses();

        String last = null;
        var count = 0;
        for (var line = connection.responseWithin(2000); line != null; line = connection.responseWithin(500)) {
            last = line;
            count++;
        }
        assertNotNull(last);
        assertTrue(last.contains("413-1"), last);
        assertTrue(count < PIPELINED_REQUESTS, "the backlog was dropped, not delivered");
        assertTrue(connection.awaitClosed(5000));
        assertNull(connection.responseWithin(100));
    }
}
