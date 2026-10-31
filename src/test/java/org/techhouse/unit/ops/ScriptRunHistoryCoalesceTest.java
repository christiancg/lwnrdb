package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRecord;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptRunHistoryCoalesceTest {
    private static final String RUNS_LOG = Globals.SCRIPT_RUNS_COLLECTION_NAME + Globals.WAL_EXTENSION;
    private static final long WAIT_MILLIS = 10_000L;

    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final ScriptRunHistory history = IocContainer.get(ScriptRunHistory.class);
    private final BackgroundTaskManager taskManager = IocContainer.get(BackgroundTaskManager.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryKinds",
                ConfigKey.SCRIPT_RUN_HISTORY_KINDS.defaultValue());
        ScriptRunHistory.write(row("warm"));
        ScriptRunHistory.reset();
        taskManager.stopBackgroundWorkers();
        taskManager.startBackgroundWorkers();
        history.startSweep();
    }

    @AfterEach
    public void tearDown() throws Exception {
        history.stopSweep();
        taskManager.stopBackgroundWorkers();
        PageRowWriteBack.flushAll();
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryMaxQueued",
                ConfigKey.SCRIPT_RUN_HISTORY_MAX_QUEUED.defaultValue());
        ScriptRunHistory.reset();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static ScriptRunRecord row(String runId) {
        return new ScriptRunRecord(runId, ScriptRunKind.CALL_PROCEDURE, TestGlobals.DB, "job", "proc", null, null,
                "admin", "admin", System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null,
                null, false);
    }

    private static ScriptRunHistoryEvent lone(String runId) {
        return new ScriptRunHistoryEvent(row(runId));
    }

    private boolean stored(String runId) {
        return cache.getWholeCollection(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME).get(runId) != null;
    }

    @SuppressWarnings("BusyWait")
    private static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        final var deadline = System.currentTimeMillis() + WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        }
        return condition.getAsBoolean();
    }

    @Test
    public void test_two_lone_runs_within_the_window_are_written_by_one_force() throws Exception {
        try (var recorder = RecordingForcer.install(true)) {
            ScriptRunHistory.writeAll(List.of(lone("lone-a")));
            ScriptRunHistory.writeAll(List.of(lone("lone-b")));

            assertTrue(eventually(() -> ScriptRunHistory.getRecorded() == 2L), "the held runs were never written");
            assertEquals(1L, recorder.forcedNames().stream().filter(RUNS_LOG::equals).count(),
                    "two runs arriving one at a time must share one forced record");
        }
        assertTrue(stored("lone-a") && stored("lone-b"));
    }

    @Test
    public void test_stopping_the_sweep_writes_the_held_runs_without_waiting() {
        ScriptRunHistory.writeAll(List.of(lone("held-at-shutdown")));

        history.stopSweep();

        assertEquals(1L, ScriptRunHistory.getRecorded(), "shutdown must flush what the writer was holding");
        assertTrue(stored("held-at-shutdown"));
    }

    @Test
    public void test_held_runs_count_against_the_queue_cap() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptRunHistoryMaxQueued", 1);
        ScriptRunHistory.writeAll(List.of(lone("held-under-cap")));

        ScriptRunHistory.record(row("over-the-cap"));

        assertEquals(1L, ScriptRunHistory.getDropped());
        history.stopSweep();
        assertEquals(1L, ScriptRunHistory.getRecorded());
    }

    @Test
    public void test_a_retried_run_is_written_at_once() {
        ScriptRunHistory.writeAll(List.of(new ScriptRunHistoryEvent(row("retried"), 1)));

        assertEquals(1L, ScriptRunHistory.getRecorded());
        assertTrue(stored("retried"));
    }

    @Test
    public void test_without_the_sweeper_a_lone_run_is_written_at_once() {
        history.stopSweep();

        ScriptRunHistory.writeAll(List.of(lone("unscheduled")));

        assertEquals(1L, ScriptRunHistory.getRecorded());
    }

    @Test
    public void test_a_batch_takes_the_held_runs_with_it_in_one_force() throws Exception {
        ScriptRunHistory.writeAll(List.of(lone("joined-held")));

        try (var recorder = RecordingForcer.install(true)) {
            ScriptRunHistory.writeAll(List.of(lone("joined-a"), lone("joined-b")));

            assertEquals(3L, ScriptRunHistory.getRecorded());
            assertEquals(1L, recorder.forcedNames().stream().filter(RUNS_LOG::equals).count());
        }
        assertNotNull(cache.getWholeCollection(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME).get("joined-held"));
    }
}
