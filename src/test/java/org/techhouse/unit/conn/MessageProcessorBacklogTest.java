package org.techhouse.unit.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundBacklogGate;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationType;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class MessageProcessorBacklogTest {
    private static final String ADMIN = "backlogadmin";
    private static final String AUTHENTICATE = "{\"type\":\"AUTHENTICATE\",\"username\":\"" + ADMIN
            + "\",\"password\":\"password123\"}";
    private static final String BACKLOG = "\"errorCode\":\"503-16\"";
    private static final int FAKE_BACKLOG = 1_000;
    private final Configuration configuration = Configuration.getInstance();
    private final BackgroundBacklogGate gate = IocContainer.get(BackgroundBacklogGate.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private AtomicInteger inFlight;
    private final AtomicBoolean backlogFaked = new AtomicBoolean();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(ADMIN);
        final var manager = IocContainer.get(BackgroundTaskManager.class);
        TestUtils.setPrivateField(configuration, "backgroundQueueMaxEvents", manager.pending() + 1);
        TestUtils.setPrivateField(configuration, "backgroundBacklogWaitMs", 200L);
        inFlight = TestUtils.getPrivateField(manager, "inFlight", AtomicInteger.class);
        inFlight.addAndGet(FAKE_BACKLOG);
        backlogFaked.set(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        drainFakeBacklog();
        TestUtils.setPrivateField(configuration, "backgroundQueueMaxEvents",
                ConfigKey.BACKGROUND_QUEUE_MAX_EVENTS.defaultValue());
        TestUtils.setPrivateField(configuration, "backgroundBacklogWaitMs",
                ConfigKey.BACKGROUND_BACKLOG_WAIT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private void drainFakeBacklog() {
        if (backlogFaked.getAndSet(false)) {
            inFlight.addAndGet(-FAKE_BACKLOG);
        }
    }

    private static List<String> exchange(String... requests) throws Exception {
        final var out = new ByteArrayOutputStream();
        final var message = String.join("\n", requests) + "\n";
        final var socket = TestUtils.clientSocket(new ByteArrayInputStream(message.getBytes(StandardCharsets.UTF_8)),
                out);
        final var thread = new Thread(new MessageProcessor(socket));
        thread.start();
        thread.join(10_000L);
        assertFalse(thread.isAlive());
        return List.of(out.toString(StandardCharsets.UTF_8).split("\n"));
    }

    private static String save(String id) {
        return "{\"type\":\"SAVE\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\"" + TestGlobals.COLL
                + "\",\"object\":{\"_id\":\"" + id + "\",\"v\":1}}";
    }

    private static String findById(String id) {
        return "{\"type\":\"FIND_BY_ID\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"_id\":\"" + id + "\"}";
    }

    @Test
    public void awaitCapacityTimesOutWith503_16() throws Exception {
        final var responses = exchange(AUTHENTICATE, save("refused"), findById("refused"));

        assertTrue(responses.get(1).contains(BACKLOG), responses.get(1));
        assertFalse(responses.get(2).contains("\"status\":\"OK\""), "nothing was written: " + responses.get(2));
    }

    @Test
    public void readsAreNotHeldBack() throws Exception {
        final var responses = exchange(AUTHENTICATE, findById("anything"));

        assertFalse(responses.get(1).contains(BACKLOG), responses.get(1));
    }

    @Test
    public void transactionalWritesBufferAndOnlyTheCommitIsHeldBack() throws Exception {
        final var responses = exchange(AUTHENTICATE, "{\"type\":\"START_TRANSACTION\"}", save("buffered"),
                "{\"type\":\"COMMIT_TRANSACTION\"}", "{\"type\":\"ROLLBACK_TRANSACTION\"}");

        assertFalse(responses.get(2).contains(BACKLOG), "a buffered write enqueues nothing: " + responses.get(2));
        assertTrue(responses.get(3).contains(BACKLOG), responses.get(3));
        assertTrue(responses.get(4).contains("\"status\":\"OK\""), "the transaction stays open: " + responses.get(4));
    }

    @Test
    public void noLockIsHeldWhileAWriteWaits() throws Exception {
        TestUtils.setPrivateField(configuration, "backgroundBacklogWaitMs", 10_000L);
        final var outcome = new AtomicReference<>(ErrorCode.BACKGROUND_BACKLOG);
        final var waiter = new Thread(() -> outcome.set(gate.admitClientRequest(OperationType.SAVE, false)));
        waiter.start();

        assertTrue(locks.tryLockWrite(TestGlobals.DB, TestGlobals.COLL, 1_000L));
        locks.releaseWrite(TestGlobals.DB, TestGlobals.COLL);
        assertTrue(waiter.isAlive(), "the write is still waiting for room while the collection is free");

        drainFakeBacklog();
        waiter.join(10_000L);

        assertFalse(waiter.isAlive());
        assertNull(outcome.get(), "the write is served once the queue has room");
    }

    @Test
    public void aForwardedWriteIsRefusedWithoutWaiting() throws Exception {
        TestUtils.setPrivateField(configuration, "backgroundBacklogWaitMs", 10_000L);

        final var started = System.nanoTime();
        assertEquals(ErrorCode.BACKGROUND_BACKLOG, gate.admitForwardedRequest(OperationType.BULK_SAVE));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000L);
        assertNull(gate.admitForwardedRequest(OperationType.AGGREGATE));
        assertNull(gate.admitClientRequest(OperationType.COMMIT_TRANSACTION, false));
    }
}
