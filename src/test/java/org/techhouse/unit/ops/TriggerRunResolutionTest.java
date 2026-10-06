package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.OperationProcessor;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunResolution;
import org.techhouse.ops.req.ResolveTriggerRunRequest;
import org.techhouse.ops.req.SaveRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerRunResolutionTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final String RUN_ID = "run-resolution-race";
    private static final int CONTENDERS = 8;

    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final CopyOnWriteArrayList<TriggerEvent> captured = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        IocContainer.get(TriggerExecutor.class).stop();
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        captured.clear();
        triggerExecutor.stop();
        triggerExecutor.start(captured::add);
        saveLiveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        AdminOperationHelper.saveTriggerRun(new AdminTriggerRunEntry(RUN_ID, 0L, TriggerRunLog.currentNodeId(),
                TestGlobals.DB, TestGlobals.COLL, "audit", "recalc", EventType.UPDATED, false, "alice", 0,
                System.currentTimeMillis(), List.of("live"), List.of()));
        TriggerRunLog.markAttempt(RUN_ID, TriggerRunStatus.DEAD, 3, "boom", 0L);
    }

    private static void saveLiveDocument() {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString("live"));
        object.addProperty("value", 1L);
        final var request = new SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(object);
        request.set_id("live");
        IocContainer.get(OperationProcessor.class).processMessage(request);
    }

    private List<Boolean> resolveConcurrently(List<String> decisions) throws Exception {
        final var start = new CountDownLatch(1);
        final var tasks = new ArrayList<Callable<Boolean>>();
        for (final var decision : decisions) {
            tasks.add(() -> {
                start.await();
                return TriggerRunResolution.resolveLocal(RUN_ID, decision);
            });
        }
        try (var pool = Executors.newFixedThreadPool(decisions.size())) {
            final var futures = new ArrayList<Future<Boolean>>();
            for (final var task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            final var results = new ArrayList<Boolean>();
            for (final var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        }
    }

    private static void awaitQueueDrain() throws InterruptedException {
        Thread.sleep(200L);
    }

    @Test
    public void test_concurrent_replays_of_one_dead_run_submit_it_once() throws Exception {
        final var results = resolveConcurrently(
                java.util.Collections.nCopies(CONTENDERS, ResolveTriggerRunRequest.DECISION_REPLAY));
        awaitQueueDrain();

        assertEquals(1, results.stream().filter(Boolean::booleanValue).count(),
                "only the replay that found the run dead may requeue it");
        assertEquals(1, captured.size(), "a run submitted twice under one id applies its trigger body twice");
    }

    @Test
    public void test_a_discard_racing_replays_never_leaves_a_discarded_run_queued() throws Exception {
        final var decisions = new ArrayList<String>();
        decisions.add(ResolveTriggerRunRequest.DECISION_DISCARD);
        for (var i = 1; i < CONTENDERS; i++) {
            decisions.add(ResolveTriggerRunRequest.DECISION_REPLAY);
        }

        final var results = resolveConcurrently(decisions);
        awaitQueueDrain();

        final var winningReplays = results.subList(1, results.size()).stream().filter(Boolean::booleanValue).count();
        assertTrue(winningReplays <= 1, "at most one replay may find the run dead");
        assertEquals(winningReplays, captured.size(),
                "a replay that lost to the discard must not have queued the run it no longer found");
    }
}
