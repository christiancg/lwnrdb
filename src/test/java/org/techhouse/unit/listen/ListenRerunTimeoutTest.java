package org.techhouse.unit.listen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.conn.ClientTracker;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ioc.IocContainer;
import org.techhouse.listen.ListenManager;
import org.techhouse.listen.ListenProcessorThread;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.req.AggregateRequest;
import org.techhouse.ops.req.BulkSaveRequest;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ListenRerunTimeoutTest {
    private static final String LISTENER = "listen_rerun_timeout_admin";
    private static final long DEADLINE_MILLIS = 5000;
    private static final long SLOW_STEP_MILLIS = 100;
    private static final int DOCUMENTS = 600;
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private UUID clientId;
    private ByteArrayOutputStream pushed;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.ensureAdminUser(LISTENER);
        final var objects = new ArrayList<JsonObject>();
        for (var i = 0; i < DOCUMENTS; i++) {
            final var document = new JsonObject();
            document.addProperty("_id", "doc-" + i);
            document.addProperty("n", i);
            objects.add(document);
        }
        final var bulk = new BulkSaveRequest(TestGlobals.DB, TestGlobals.COLL);
        bulk.setObjects(objects);
        IocContainer.get(OperationProcessor.class).processMessage(bulk);
        clientId = clientTracker.registerForwardedClient(LISTENER);
        pushed = new ByteArrayOutputStream();
        clientTracker.registerOutbound(clientId, TestUtils.inlineOutbound(pushed));
    }

    @AfterEach
    void tearDown() throws Exception {
        clientTracker.removeById(clientId);
        TestUtils.setPrivateField(configuration, "listenRerunTimeoutMs",
                ConfigKey.LISTEN_RERUN_TIMEOUT_MS.defaultValue());
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", ConfigKey.AGGREGATE_TIMEOUT_MS.defaultValue());
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @Test
    public void test_a_slow_rerun_is_cut_at_the_listen_rerun_timeout() throws Exception {
        TestUtils.setPrivateField(configuration, "listenRerunTimeoutMs", 10);
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", 0);
        final var listenId = rerunOnce();
        assertFalse(pushed.toString(StandardCharsets.UTF_8).contains(listenId.toString()),
                "a re-run past listenRerunTimeoutMs must push nothing");
    }

    @Test
    public void test_a_rerun_is_not_bounded_by_the_aggregate_timeout() throws Exception {
        TestUtils.setPrivateField(configuration, "listenRerunTimeoutMs", 60000);
        TestUtils.setPrivateField(configuration, "aggregateTimeoutMs", 10);
        final var listenId = rerunOnce();
        assertTrue(pushed.toString(StandardCharsets.UTF_8).contains(listenId.toString()),
                "a re-run within listenRerunTimeoutMs pushes even when it outlasts aggregateTimeoutMs");
    }

    private UUID rerunOnce() throws InterruptedException {
        final var manager = new ListenManager();
        final var request = new AggregateRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setAggregationSteps(List.of(new SlowSkipStep()));
        request.setDirtyRead(true);
        final var listenId = manager.register(clientId, LISTENER, request, "stale-hash", 0);
        manager.markDelivered(listenId);
        final var queue = new LinkedBlockingQueue<UUID>();
        queue.offer(listenId);
        final var worker = Thread.ofPlatform().daemon().start(new ListenProcessorThread(queue, manager));
        try {
            awaitTrue(() -> queue.isEmpty() && worker.getState() == Thread.State.WAITING);
        } finally {
            worker.interrupt();
            worker.join(DEADLINE_MILLIS);
        }
        return listenId;
    }

    private static void awaitTrue(BooleanSupplier condition) {
        final var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DEADLINE_MILLIS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class SlowSkipStep extends SkipAggregationStep {
        SlowSkipStep() {
            super(0);
        }

        @Override
        public AggregationStepType getType() {
            try {
                Thread.sleep(SLOW_STEP_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.getType();
        }
    }
}
