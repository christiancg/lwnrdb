package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.conn.MessageProcessor;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.ops.ErrorCode;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.ListenRequest;
import org.techhouse.ops.req.StopListenRequest;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.ops.resp.ListenResponse;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenRetentionTest {
    private static final String USER = "listen_retention_admin";
    private static final long DEADLINE_MILLIS = 5000;
    private final OperationProcessor processor = IocContainer.get(OperationProcessor.class);
    private final ListenManager listenManager = IocContainer.get(ListenManager.class);
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private final List<UUID> clients = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.createAdmin(USER);
    }

    @AfterEach
    void tearDown() throws Exception {
        for (final var clientId : clients) {
            listenManager.unregisterAllForClient(clientId);
            clientTracker.removeById(clientId);
        }
        TestUtils.setPrivateField(configuration, "listenMaxRequestBytes",
                ConfigKey.LISTEN_MAX_REQUEST_BYTES.defaultValue());
        TestUtils.setPrivateField(configuration, "listenMaxRetainedBytes",
                ConfigKey.LISTEN_MAX_RETAINED_BYTES.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private UUID client() {
        final var clientId = clientTracker.registerForwardedClient(USER);
        clients.add(clientId);
        return clientId;
    }

    private ListenResponse listen(UUID clientId) {
        final var request = new ListenRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SkipAggregationStep(0)));
        return assertInstanceOf(ListenResponse.class, processor.processMessage(request, clientId));
    }

    private String runLines(String listenLine) throws Exception {
        final var messages = "{\"type\":\"AUTHENTICATE\",\"username\":\"" + USER + "\",\"password\":\"password123\"}\n"
                + listenLine + "\n";
        final var out = new ByteArrayOutputStream();
        final var socket = TestUtils.clientSocket(new ByteArrayInputStream(messages.getBytes(StandardCharsets.UTF_8)),
                out);
        final var thread = new Thread(new MessageProcessor(socket));
        thread.start();
        thread.join(DEADLINE_MILLIS);
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String listenLineOf(int bytes) {
        final var head = "{\"type\":\"LISTEN\",\"databaseName\":\"" + TestGlobals.DB + "\",\"collectionName\":\""
                + TestGlobals.COLL + "\",\"aggregationSteps\":[]";
        return head + " ".repeat(bytes - head.length() - 1) + "}";
    }

    @Test
    public void test_an_oversized_listen_line_is_refused_before_it_runs() throws Exception {
        TestUtils.setPrivateField(configuration, "listenMaxRequestBytes", "1Kb");
        final var response = runLines(listenLineOf(4096));
        assertTrue(response.contains(ErrorCode.LISTEN_REQUEST_TOO_LARGE.getCode()), response);
        assertFalse(response.contains("listenId\":\""), response);
        assertEquals(0L, listenManager.retainedBytes());
        assertEquals(0, listenManager.listensOf(USER));
    }

    @Test
    public void test_a_listen_line_exactly_at_the_cap_is_accepted_and_one_byte_over_is_refused() throws Exception {
        TestUtils.setPrivateField(configuration, "listenMaxRequestBytes", "512");
        final var atCap = runLines(listenLineOf(512));
        assertTrue(atCap.contains("\"listenId\""), atCap);
        assertFalse(atCap.contains(ErrorCode.LISTEN_REQUEST_TOO_LARGE.getCode()), atCap);
        final var overCap = runLines(listenLineOf(513));
        assertTrue(overCap.contains(ErrorCode.LISTEN_REQUEST_TOO_LARGE.getCode()), overCap);
    }

    @Test
    public void test_retained_bytes_are_released_on_stop_disconnect_and_overflow() {
        final var clientId = client();
        final var listenId = listen(clientId).getListenId();
        final var oneListen = listenManager.retainedBytes();
        assertTrue(oneListen > 0);
        assertEquals(1, listenManager.listensOf(USER));
        final var stop = new StopListenRequest();
        stop.setListenId(listenId);
        processor.processMessage(stop, clientId);
        assertEquals(0L, listenManager.retainedBytes());
        assertEquals(0, listenManager.listensOf(USER));

        listen(clientId);
        listen(clientId);
        assertEquals(2 * oneListen, listenManager.retainedBytes());
        listenManager.unregisterAllForClient(clientId);
        assertEquals(0L, listenManager.retainedBytes());
        assertEquals(0, listenManager.listensOf(USER));
    }

    @Test
    public void test_an_ended_listen_keeps_its_bytes_until_it_is_consumed_or_its_connection_closes() throws Exception {
        final var clientId = client();
        final var revoked = UUID.fromString(listen(clientId).getListenId());
        listenManager.end(revoked, ListenManager.ACCESS_REVOKED);
        assertEquals(1, listenManager.listensOf(USER));
        listenManager.unregisterAllForClient(clientId);
        assertEquals(0L, listenManager.retainedBytes());

        final var manager = new ListenManager();
        assertNull(manager.reserve(USER, 100));
        final var ended = manager.register(clientId, USER, dirtyRequest(), "hash", 100);
        manager.end(ended, ListenManager.ACCESS_REVOKED);
        manager.markDelivered(ended);
        assertEquals(100L, manager.retainedBytes());
        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(ended);
        final var worker = Thread.ofPlatform().daemon().start(new ListenProcessorThread(queue, manager));
        try {
            awaitTrue(() -> manager.retainedBytes() == 0L);
        } finally {
            worker.interrupt();
            worker.join(DEADLINE_MILLIS);
        }
        assertEquals(0, manager.listensOf(USER));
    }

    @Test
    public void test_the_node_budget_refuses_with_503_15_and_admits_exactly_at_the_cap() throws Exception {
        final var clientId = client();
        listen(clientId);
        final var oneListen = listenManager.retainedBytes();
        TestUtils.setPrivateField(configuration, "listenMaxRetainedBytes", String.valueOf(2 * oneListen));
        listen(clientId);
        final var request = new ListenRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SkipAggregationStep(0)));
        final var refused = processor.processMessage(request, clientId);
        assertEquals(ErrorCode.LISTEN_RETAINED_BYTES_EXHAUSTED.getCode(), refused.getErrorCode());
        assertEquals(2 * oneListen, listenManager.retainedBytes());
        assertEquals(2, listenManager.listensOf(USER));
    }

    @Test
    public void test_a_reservation_exactly_at_the_cap_fits_and_one_byte_over_does_not() throws Exception {
        TestUtils.setPrivateField(configuration, "listenMaxRetainedBytes", "1000");
        final var manager = new ListenManager();
        assertNull(manager.reserve(USER, 1000));
        assertEquals(ErrorCode.LISTEN_RETAINED_BYTES_EXHAUSTED, manager.reserve("someone_else", 1));
        manager.release(USER, 1000);
        assertNull(manager.reserve("someone_else", 1));
        assertEquals(ErrorCode.LISTEN_RETAINED_BYTES_EXHAUSTED, manager.reserve(USER, 1000));
    }

    @Test
    public void test_concurrent_registration_and_disconnect_never_drive_the_counter_negative() throws Exception {
        final var manager = new ListenManager();
        final var clientId = UUID.randomUUID();
        final var start = new CountDownLatch(1);
        final var done = new AtomicBoolean();
        final var lowest = new AtomicLong();
        final var workers = new ArrayList<Thread>();
        for (var i = 0; i < 4; i++) {
            workers.add(Thread.ofPlatform().start(() -> {
                awaitLatch(start);
                for (var round = 0; round < 500; round++) {
                    if (manager.reserve(USER, 10) == null) {
                        final var id = manager.register(clientId, USER, dirtyRequest(), "hash", 10);
                        if (round % 3 == 0) {
                            manager.end(id, ListenManager.COLLECTION_DROPPED);
                        }
                    }
                }
            }));
            workers.add(Thread.ofPlatform().start(() -> {
                awaitLatch(start);
                for (var round = 0; round < 500; round++) {
                    manager.unregisterAllForClient(clientId);
                }
            }));
        }
        final var sampler = Thread.ofPlatform().start(() -> {
            while (!done.get()) {
                lowest.accumulateAndGet(manager.retainedBytes(), Math::min);
            }
        });
        start.countDown();
        for (final var worker : workers) {
            worker.join(DEADLINE_MILLIS * 4);
        }
        done.set(true);
        sampler.join(DEADLINE_MILLIS);
        manager.unregisterAllForClient(clientId);
        assertEquals(0L, lowest.get());
        assertEquals(0L, manager.retainedBytes());
        assertEquals(0, manager.listensOf(USER));
    }

    private static AggregateRequest dirtyRequest() {
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of());
        request.setDirtyRead(true);
        return request;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(DEADLINE_MILLIS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTrue(BooleanSupplier condition) {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean());
    }
}
