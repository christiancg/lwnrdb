package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.admin.AdminDbEntry;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.ScriptRunHistory;
import org.techhouse.ops.ScriptRunKind;
import org.techhouse.ops.ScriptRunRecord;
import org.techhouse.simplejs.host.ScriptRunMetrics;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class ScriptRunHistoryTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final Cache cache = IocContainer.get(Cache.class);

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        setConfig("scriptRunHistoryEnabled", false);
        ScriptRunHistory.reset();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void enableHistory() throws Exception {
        setConfig("scriptRunHistoryEnabled", true);
        setConfig("scriptRunHistoryKinds", "CALL_PROCEDURE,TRIGGER,SCHEDULE");
        setConfig("scriptRunHistoryRetentionMs", 604_800_000L);
        setConfig("scriptRunHistoryIncludeLogs", false);
        setConfig("scriptRunHistoryMaxErrorChars", 2_000);
        ScriptRunHistory.reset();
    }

    private static <T> void setConfig(String field, T value) throws Exception {
        TestUtils.setPrivateField(configuration, field, value);
    }

    private static ScriptRunRecord row(String runId, ScriptRunKind kind, long startedAt, String outcome) {
        return new ScriptRunRecord(runId, kind, TestGlobals.DB, "job", "proc", TestGlobals.COLL, "CREATED", "u1", "u2",
                startedAt, 12L, 1, outcome, null, null, List.of("frame (main:1:1)"),
                new ScriptRunMetrics(9L, 100L, 8L, 200L, 2L, 12L), List.of("a log line"), false);
    }

    private static org.techhouse.ejson.elements.JsonObject readRow(String id) {
        final var entries = cache.getWholeCollection(TestGlobals.DB, Globals.SCRIPT_RUNS_COLLECTION_NAME);
        final var entry = entries.get(id);
        return entry == null ? null : entry.getData();
    }

    @Test
    public void test_kind_filter_only_admits_the_configured_kinds() {
        assertTrue(ScriptRunHistory.recordsKind(ScriptRunKind.TRIGGER));
        assertTrue(ScriptRunHistory.recordsKind(ScriptRunKind.SCHEDULE));
        assertTrue(ScriptRunHistory.recordsKind(ScriptRunKind.CALL_PROCEDURE));
        assertFalse(ScriptRunHistory.recordsKind(ScriptRunKind.RUN_SCRIPT));
        assertFalse(ScriptRunHistory.recordsKind(ScriptRunKind.BEFORE_HOOK));
        assertFalse(ScriptRunHistory.recordsKind(null));
    }

    @Test
    public void test_an_empty_kind_list_records_nothing() throws Exception {
        setConfig("scriptRunHistoryKinds", "");
        assertFalse(ScriptRunHistory.recordsKind(ScriptRunKind.TRIGGER));
    }

    @Test
    public void test_kinds_are_matched_ignoring_case_and_spacing() throws Exception {
        setConfig("scriptRunHistoryKinds", " trigger , Run_Script ");
        assertTrue(ScriptRunHistory.recordsKind(ScriptRunKind.TRIGGER));
        assertTrue(ScriptRunHistory.recordsKind(ScriptRunKind.RUN_SCRIPT));
        assertFalse(ScriptRunHistory.recordsKind(ScriptRunKind.SCHEDULE));
    }

    // A database only grows the collection once a row lands in it, which is why CREATE_DATABASE does not
    // create it: a database that never runs a script keeps the collection list it always had.
    @Test
    public void test_the_collection_is_created_lazily_by_the_first_row() throws Exception {
        final var freshDb = "lazyHistoryDb";
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(freshDb));
        IocContainer.get(org.techhouse.fs.FileSystem.class).createDatabaseFolder(freshDb);
        assertNull(cache.getAdminCollectionEntry(freshDb, Globals.SCRIPT_RUNS_COLLECTION_NAME));

        ScriptRunHistory.write(new ScriptRunRecord("run-lazy", ScriptRunKind.TRIGGER, freshDb, "job", "proc", null,
                null, "u1", "u2", System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null,
                null, false));
        assertNotNull(cache.getAdminCollectionEntry(freshDb, Globals.SCRIPT_RUNS_COLLECTION_NAME));
        assertEquals(1L, ScriptRunHistory.getRecorded());
    }

    @Test
    public void test_a_row_carries_the_full_shape() {
        ScriptRunHistory.write(row("run-shape", ScriptRunKind.TRIGGER, 1_700_000_000_000L, ScriptRunRecord.OUTCOME_OK));
        final var document = readRow("run-shape");
        assertNotNull(document);
        assertEquals("TRIGGER", document.get("kind").asJsonString().getValue());
        assertEquals("job", document.get("name").asJsonString().getValue());
        assertEquals("proc", document.get("procedure").asJsonString().getValue());
        assertEquals(TestGlobals.COLL, document.get("collection").asJsonString().getValue());
        assertEquals("CREATED", document.get("event").asJsonString().getValue());
        assertEquals("u1", document.get("username").asJsonString().getValue());
        assertEquals("u2", document.get("actingUser").asJsonString().getValue());
        assertEquals(ScriptRunRecord.OUTCOME_OK, document.get("outcome").asJsonString().getValue());
        assertEquals(1_700_000_000_000L, document.get("startedAt").asJsonNumber().getValue().longValue());
        assertEquals(1, document.get("attempt").asJsonNumber().asInteger());
        assertEquals(9L,
                document.get("metrics").asJsonObject().get("instructions").asJsonNumber().getValue().longValue());
        assertEquals(1, document.get("stack").asJsonArray().size());
    }

    // Logs are off by default: a chatty procedure would otherwise make each row as large as its output.
    @Test
    public void test_logs_are_omitted_unless_configured() throws Exception {
        ScriptRunHistory.write(
                row("run-nologs", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        assertEquals(0, Objects.requireNonNull(readRow("run-nologs")).get("logs").asJsonArray().size());

        setConfig("scriptRunHistoryIncludeLogs", true);
        ScriptRunHistory
                .write(row("run-logs", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        assertEquals(1, Objects.requireNonNull(readRow("run-logs")).get("logs").asJsonArray().size());
    }

    @Test
    public void test_a_long_error_message_is_clipped() throws Exception {
        setConfig("scriptRunHistoryMaxErrorChars", 10);
        final var longMessage = "e".repeat(500);
        ScriptRunHistory.write(new ScriptRunRecord("run-clip", ScriptRunKind.TRIGGER, TestGlobals.DB, "job", "proc",
                TestGlobals.COLL, "CREATED", "u1", "u2", System.currentTimeMillis(), 1L, 1,
                ScriptRunRecord.OUTCOME_ERROR, "Error", longMessage, null, null, null, false));
        final var stored = Objects.requireNonNull(readRow("run-clip")).get("errorMessage").asJsonString().getValue();
        assertEquals(11, stored.length(), "expected 10 characters plus the ellipsis, got " + stored);
    }

    @Test
    public void test_a_disabled_history_writes_nothing() throws Exception {
        setConfig("scriptRunHistoryEnabled", false);
        ScriptRunHistory.write(
                row("run-disabled", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        assertEquals(0L, ScriptRunHistory.getRecorded());
    }

    // History is diagnostics: a row that cannot be written is counted and dropped, never thrown.
    @Test
    public void test_an_unknown_database_is_dropped_rather_than_thrown() {
        ScriptRunHistory.write(new ScriptRunRecord("run-nodb", ScriptRunKind.TRIGGER, "noSuchDatabase", "job", "proc",
                null, null, "u1", "u2", System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null,
                null, null, false));
        assertEquals(0L, ScriptRunHistory.getRecorded());
        assertEquals(1L, ScriptRunHistory.getDropped());
    }

    @Test
    public void test_a_null_record_is_ignored() {
        ScriptRunHistory.write(null);
        ScriptRunHistory.record(null);
        assertEquals(0L, ScriptRunHistory.getRecorded());
        assertEquals(0L, ScriptRunHistory.getDropped());
    }

    @Test
    public void test_the_sweep_deletes_only_rows_past_the_retention() throws Exception {
        final var now = System.currentTimeMillis();
        ScriptRunHistory.write(row("run-old", ScriptRunKind.TRIGGER, now - 100_000L, ScriptRunRecord.OUTCOME_OK));
        ScriptRunHistory.write(row("run-new", ScriptRunKind.TRIGGER, now, ScriptRunRecord.OUTCOME_OK));
        setConfig("scriptRunHistoryRetentionMs", 50_000L);
        ScriptRunHistory.sweepOnce();
        assertNull(readRow("run-old"));
        assertNotNull(readRow("run-new"));
    }

    @Test
    public void test_the_sweep_is_a_no_op_when_history_is_disabled() throws Exception {
        ScriptRunHistory.write(row("run-keep", ScriptRunKind.TRIGGER, 1L, ScriptRunRecord.OUTCOME_OK));
        setConfig("scriptRunHistoryEnabled", false);
        setConfig("scriptRunHistoryRetentionMs", 1L);
        ScriptRunHistory.sweepOnce();
        assertNotNull(readRow("run-keep"));
    }

    @Test
    public void test_a_row_is_saved_under_the_run_id() {
        ScriptRunHistory.write(
                row("run-id", ScriptRunKind.SCHEDULE, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_SKIPPED));
        final var document = readRow("run-id");
        assertNotNull(document);
        assertEquals("run-id", document.get("runId").asJsonString().getValue());
        assertEquals(ScriptRunRecord.OUTCOME_SKIPPED, document.get("outcome").asJsonString().getValue());
    }

    @Test
    public void test_record_queues_without_writing() {
        ScriptRunHistory.record(
                row("run-ignored", ScriptRunKind.RUN_SCRIPT, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        ScriptRunHistory.record(
                row("run-queued", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        assertEquals(0L, ScriptRunHistory.getRecorded());
    }

    // A second row for the same database reuses the memo rather than re-checking admin metadata.
    @Test
    public void test_two_rows_share_one_collection_creation() {
        ScriptRunHistory
                .write(row("first", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        ScriptRunHistory
                .write(row("second", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        assertEquals(2L, ScriptRunHistory.getRecorded());
        assertNotNull(readRow("first"));
        assertNotNull(readRow("second"));
    }

    @Test
    public void test_a_row_with_no_run_id_still_lands() {
        ScriptRunHistory.write(new ScriptRunRecord(null, ScriptRunKind.TRIGGER, TestGlobals.DB, "job", "proc", null,
                null, "u1", "u2", System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_ERROR, "Error", "boom",
                null, null, null, false));
        assertEquals(1L, ScriptRunHistory.getRecorded());
    }

    // A database that never ran a script has no collection to prune, which the sweep must skip rather
    // than treat as an empty one.
    @Test
    public void test_the_sweep_skips_a_database_with_no_history_collection() throws Exception {
        final var freshDb = "unsweptHistoryDb";
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(freshDb));
        IocContainer.get(org.techhouse.fs.FileSystem.class).createDatabaseFolder(freshDb);
        setConfig("scriptRunHistoryRetentionMs", 1L);

        ScriptRunHistory.sweepOnce();

        assertNull(cache.getAdminCollectionEntry(freshDb, Globals.SCRIPT_RUNS_COLLECTION_NAME));
        assertEquals(0L, ScriptRunHistory.getDropped());
    }

    // The sweep thread is started once at boot and stopped in the ordered shutdown, before the background
    // queue drains so queued rows still land.
    @Test
    public void test_the_sweep_thread_starts_and_stops() throws Exception {
        final var history = IocContainer.get(ScriptRunHistory.class);
        history.startSweep();
        assertNotNull(
                TestUtils.getPrivateField(history, "sweeper", java.util.concurrent.ScheduledExecutorService.class));

        history.startSweep();
        history.stopSweep();
        assertNull(TestUtils.getPrivateField(history, "sweeper", java.util.concurrent.ScheduledExecutorService.class));

        history.stopSweep();
    }

    @Test
    public void test_the_sweep_thread_does_not_start_when_history_is_disabled() throws Exception {
        setConfig("scriptRunHistoryEnabled", false);
        final var history = IocContainer.get(ScriptRunHistory.class);
        history.startSweep();
        assertNull(TestUtils.getPrivateField(history, "sweeper", java.util.concurrent.ScheduledExecutorService.class));
    }

    // Under clustering the sweep runs only where this node owns the collection, so N nodes do not each
    // issue the same deletes.
    @Test
    public void test_the_sweep_skips_a_collection_this_node_does_not_own() throws Exception {
        final var config = Configuration.getInstance();
        final var clusterWasEnabled = config.isClusterEnabled();
        ScriptRunHistory.write(row("run-foreign", ScriptRunKind.TRIGGER, 1L, ScriptRunRecord.OUTCOME_OK));
        setConfig("scriptRunHistoryRetentionMs", 1L);
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        try {
            ScriptRunHistory.sweepOnce();
            assertNotNull(readRow("run-foreign"), "an unowned collection must not be pruned from here");
        } finally {
            TestUtils.setPrivateField(config, "clusterEnabled", clusterWasEnabled);
        }
    }

    // A row that the write path refuses is counted and dropped, never thrown: the run it describes has
    // already committed its effects and must not fail for want of a record.
    @Test
    public void test_a_refused_write_is_counted_and_dropped() throws Exception {
        final var config = Configuration.getInstance();
        final var originalMaxEntry = config.getMaxEntrySize();
        TestUtils.setPrivateField(config, "maxEntrySize", 8L);
        try {
            ScriptRunHistory.write(
                    row("run-too-big", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
            assertEquals(0L, ScriptRunHistory.getRecorded());
            assertEquals(1L, ScriptRunHistory.getDropped());
        } finally {
            TestUtils.setPrivateField(config, "maxEntrySize", originalMaxEntry);
        }
    }

    // With clustering on the write consults the router, and then takes the same ownership guard every
    // other write takes. A node that owns nothing refuses it and drops the row rather than writing a
    // copy the owner will never see.
    @Test
    public void test_a_write_routes_and_respects_the_ownership_guard() throws Exception {
        final var config = Configuration.getInstance();
        final var clusterWasEnabled = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        try {
            ScriptRunHistory.write(
                    row("run-routed", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
            assertNull(readRow("run-routed"));
            assertEquals(1L, ScriptRunHistory.getDropped());
        } finally {
            TestUtils.setPrivateField(config, "clusterEnabled", clusterWasEnabled);
        }
    }

    @Test
    public void test_the_periodic_wrapper_swallows_whatever_the_sweep_throws() {
        ScriptRunHistory.sweepQuietly();
    }

    // A database whose history collection cannot be created drops the row rather than failing the run.
    @Test
    public void test_a_collection_that_cannot_be_created_drops_the_row() throws Exception {
        final var config = Configuration.getInstance();
        final var freshDb = "uncreatableHistoryDb";
        AdminOperationHelper.saveDatabaseEntry(new AdminDbEntry(freshDb));
        IocContainer.get(org.techhouse.fs.FileSystem.class).createDatabaseFolder(freshDb);
        final var clusterWasEnabled = config.isClusterEnabled();
        TestUtils.setPrivateField(config, "clusterEnabled", true);
        try {
            ScriptRunHistory.write(new ScriptRunRecord("run-uncreatable", ScriptRunKind.TRIGGER, freshDb, "job", "proc",
                    null, null, "u1", "u2", System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null,
                    null, null, null, false));
            assertEquals(0L, ScriptRunHistory.getRecorded());
            assertEquals(1L, ScriptRunHistory.getDropped());
        } finally {
            TestUtils.setPrivateField(config, "clusterEnabled", clusterWasEnabled);
        }
    }

    // The row names the node that ran the script, which is what makes a cluster-wide history readable.
    @Test
    public void test_a_row_names_the_node_that_ran_it() throws Exception {
        final var membership = IocContainer.get(org.techhouse.cluster.membership.MembershipService.class);
        final var self = new org.techhouse.cluster.NodeInfo("self", "127.0.0.1", 7777,
                org.techhouse.cluster.NodeState.ALIVE, 1L, 1L);
        TestUtils.setPrivateField(membership, "self", self);
        try {
            ScriptRunHistory.write(
                    row("run-node", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
            assertEquals("127.0.0.1:7777", Objects.requireNonNull(readRow("run-node")).get("node").asJsonString().getValue());
        } finally {
            TestUtils.setPrivateField(membership, "self", null);
        }
    }

    @Test
    public void test_a_row_from_a_standalone_node_is_labelled_local() throws Exception {
        // Cleared explicitly: another suite in this JVM may have left a membership identity behind.
        TestUtils.setPrivateField(IocContainer.get(org.techhouse.cluster.membership.MembershipService.class), "self",
                null);
        ScriptRunHistory
                .write(row("run-local", ScriptRunKind.TRIGGER, System.currentTimeMillis(), ScriptRunRecord.OUTCOME_OK));
        assertEquals("local", Objects.requireNonNull(readRow("run-local")).get("node").asJsonString().getValue());
    }

    // A run kind is normally never null - record() filters those out - but write() is also reached from
    // the background worker, where the row is whatever was queued.
    @Test
    public void test_a_row_without_a_kind_still_lands() {
        ScriptRunHistory.write(new ScriptRunRecord("run-nokind", null, TestGlobals.DB, "job", "proc", null, null, "u1",
                "u2", System.currentTimeMillis(), 1L, 1, ScriptRunRecord.OUTCOME_OK, null, null, null, null, null,
                false));
        final var document = readRow("run-nokind");
        assertNotNull(document);
        assertEquals(ScriptRunRecord.OUTCOME_OK, document.get("outcome").asJsonString().getValue());
    }
}
