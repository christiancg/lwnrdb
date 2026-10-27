package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.BackgroundTaskManager;
import org.techhouse.bckg_ops.EventProcessorHelper;
import org.techhouse.bckg_ops.events.Event;
import org.techhouse.bckg_ops.events.ScriptRunHistoryEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRecord;
import org.techhouse.ops.admin.PageRowWriteBack;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptRunHistoryBatchTest {
    private static final String OTHER_DB = "historyBatchDb";
    private static final String RUNS_LOG = Globals.SCRIPT_RUNS_COLLECTION_NAME + Globals.WAL_EXTENSION;

    private final Configuration configuration = Configuration.getInstance();
    private final Cache cache = IocContainer.get(Cache.class);
    private final BackgroundTaskManager sharedManager = IocContainer.get(BackgroundTaskManager.class);

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(OTHER_DB));
        IocContainer.get(FileSystem.class).createDatabaseFolder(OTHER_DB);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", true);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryKinds",
                ConfigKey.SCRIPT_RUN_HISTORY_KINDS.defaultValue());
        ScriptRunHistory.write(row("warm-a", TestGlobals.DB, null));
        ScriptRunHistory.write(row("warm-b", OTHER_DB, null));
        ScriptRunHistory.reset();
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        TestUtils.setPrivateStaticField(ScriptRunHistory.class, "taskManager", sharedManager);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryEnabled", false);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryIncludeLogs", false);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryMaxQueued",
                ConfigKey.SCRIPT_RUN_HISTORY_MAX_QUEUED.defaultValue());
        ScriptRunHistory.reset();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private static ScriptRunRecord row(String runId, String dbName, List<String> logs) {
        return new ScriptRunRecord(runId, ScriptRunKind.CALL_PROCEDURE, dbName, "job", "proc", null, null, "u1", "u1",
                System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null, logs, false);
    }

    private int historyRows(String dbName) {
        return cache.getWholeCollection(dbName, Globals.SCRIPT_RUNS_COLLECTION_NAME).size();
    }

    @Test
    public void test_runs_of_two_databases_are_two_bulk_writes() throws Exception {
        final var batch = new ArrayList<Event>();
        for (var i = 0; i < 300; i++) {
            batch.add(new ScriptRunHistoryEvent(row("batch-" + i, i % 2 == 0 ? TestGlobals.DB : OTHER_DB, null)));
        }
        try (var recorder = RecordingForcer.install(true)) {
            EventProcessorHelper.processBatch(batch);

            assertEquals(2, recorder.forcedNames().stream().filter(RUNS_LOG::equals).count());
        }

        assertEquals(300L, ScriptRunHistory.getRecorded());
        assertEquals(0L, ScriptRunHistory.getDropped());
        assertEquals(151, historyRows(TestGlobals.DB));
        assertEquals(151, historyRows(OTHER_DB));
    }

    @Test
    public void test_a_run_queued_twice_is_written_once() {
        ScriptRunHistory.writeAll(List.of(new ScriptRunHistoryEvent(row("twice", TestGlobals.DB, null)),
                new ScriptRunHistoryEvent(row("once", TestGlobals.DB, null)),
                new ScriptRunHistoryEvent(row("twice", TestGlobals.DB, null), 1)));

        assertEquals(2L, ScriptRunHistory.getRecorded());
        assertEquals(3, historyRows(TestGlobals.DB));
    }

    @Test
    public void test_an_oversized_row_drops_only_itself() throws Exception {
        TestUtils.setPrivateField(configuration, "scriptRunHistoryIncludeLogs", true);
        final var huge = List.of("x".repeat((int) configuration.getMaxEntrySize() + 1));

        ScriptRunHistory.writeAll(List.of(new ScriptRunHistoryEvent(row("small-1", TestGlobals.DB, null)),
                new ScriptRunHistoryEvent(row("huge", TestGlobals.DB, huge)),
                new ScriptRunHistoryEvent(row("small-2", TestGlobals.DB, null))));

        assertEquals(2L, ScriptRunHistory.getRecorded());
        assertEquals(1L, ScriptRunHistory.getDropped());
        final var rows = cache.getWholeCollection(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME);
        assertNotNull(rows.get("small-2"));
        assertNull(rows.get("huge"));
    }

    @Test
    public void test_a_batch_for_a_vanished_database_is_dropped() {
        ScriptRunHistory.writeAll(List.of(new ScriptRunHistoryEvent(row("gone-1", "vanishedDb", null)),
                new ScriptRunHistoryEvent(row("gone-2", "vanishedDb", null)), new ScriptRunHistoryEvent(null)));

        assertEquals(0L, ScriptRunHistory.getRecorded());
        assertEquals(2L, ScriptRunHistory.getDropped());
    }

    @Test
    public void test_a_full_queue_drops_runs_instead_of_queueing_them() throws Exception {
        final var manager = new BackgroundTaskManager();
        TestUtils.setPrivateStaticField(ScriptRunHistory.class, "taskManager", manager);
        TestUtils.setPrivateField(configuration, "scriptRunHistoryMaxQueued", 5);

        for (var i = 0; i < 10; i++) {
            ScriptRunHistory.record(row("capped-" + i, TestGlobals.DB, null));
        }

        assertEquals(5, manager.queuedHistory());
        assertEquals(5L, ScriptRunHistory.getDropped());
        manager.stopBackgroundWorkers();
        ScriptRunHistory.record(row("after-drain", TestGlobals.DB, null));
        assertEquals(1, manager.queuedHistory());
        manager.stopBackgroundWorkers();
    }
}
