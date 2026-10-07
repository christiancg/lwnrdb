package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.TriggerExecutor;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.bckg_ops.events.TriggerEvent;
import org.techhouse.cache.Cache;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;
import org.techhouse.ops.AdminOperationHelper;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerRunRecoveryTest {
    private static final Configuration configuration = Configuration.getInstance();

    private final TriggerExecutor triggerExecutor = IocContainer.get(TriggerExecutor.class);
    private final Cache cache = IocContainer.get(Cache.class);
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
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    private static void sleep() {
        try {
            Thread.sleep((long) 100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static JsonObject document(String id) {
        final var object = new JsonObject();
        object.add(Globals.PK_FIELD, new JsonString(id));
        object.addProperty("value", 1L);
        return object;
    }

    private static void writeRecord(String runId, String nodeId, EventType type, List<String> ids,
            List<JsonObject> documents, long firedAt) throws Exception {
        AdminOperationHelper.saveTriggerRun(new AdminTriggerRunEntry(runId, 0L, nodeId, TestGlobals.DB,
                TestGlobals.COLL, "audit", "recalc", type, false, "alice", 0, firedAt, ids, documents));
    }

    private void saveDocument() {
        final var entry = DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, document("live"));
        cache.addEntryToCache(TestGlobals.DB, TestGlobals.COLL, entry);
        final var request = new org.techhouse.ops.req.SaveRequest(TestGlobals.DB, TestGlobals.COLL);
        request.setObject(document("live"));
        request.set_id("live");
        IocContainer.get(org.techhouse.ops.OperationProcessor.class).processMessage(request);
    }

    @Test
    public void test_recovery_preserves_the_attempt_count() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        writeRecord("run-attempts", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());
        TriggerRunLog.markAttempt("run-attempts", org.techhouse.data.admin.TriggerRunStatus.PENDING, 2, "boom", 0L);

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertEquals(1, captured.size());
        assertEquals(3, captured.getFirst().getAttempt(),
                "the record counts attempts consumed, so recovery must queue the next one rather than repeat the"
                        + " last, which used to hand back an extra attempt on every restart");
    }

    @Test
    public void test_a_fresh_record_recovers_as_its_first_attempt() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        writeRecord("run-fresh", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertEquals(1, captured.size());
        assertEquals(1, captured.getFirst().getAttempt());
    }

    @Test
    public void test_a_replayed_dead_letter_starts_from_a_full_budget() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        writeRecord("run-replay", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());
        TriggerRunLog.markAttempt("run-replay", org.techhouse.data.admin.TriggerRunStatus.DEAD, 3, "boom", 0L);

        assertTrue(org.techhouse.ops.TriggerRunResolution.resolveLocal("run-replay",
                org.techhouse.ops.req.ResolveTriggerRunRequest.DECISION_REPLAY));
        sleep();

        assertEquals(1, captured.size());
        assertEquals(1, captured.getFirst().getAttempt(),
                "the on-disk counter is reset to 0, so the re-queued event must not still carry the exhausted count");
    }

    @Test
    public void test_pending_run_is_resubmitted_at_startup() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        writeRecord("run-a", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertEquals(1, captured.size());
        assertEquals("run-a", captured.getFirst().getRunId());
        assertEquals(1, captured.getFirst().getEntries().size());
    }

    @Test
    public void test_a_run_older_than_the_retention_is_still_replayed_at_startup() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        writeRecord("run-old", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), 1L);

        final var startupRuns = TriggerRunRecovery.startupRunIds();
        TriggerRunRecovery.garbageCollect();
        TriggerRunRecovery.recoverLocal(startupRuns);
        sleep();

        assertEquals(1, captured.size(), "a node down longer than the retention must still replay its own runs");
        assertEquals("run-old", captured.getFirst().getRunId());
    }

    @Test
    public void test_run_from_another_node_is_not_replayed_locally() throws Exception {
        writeRecord("run-b", "some-other-node", EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertTrue(captured.isEmpty());
        assertEquals(1, TriggerRunLog.pending().size(), "the other node's record must be left alone");
    }

    @Test
    public void test_a_run_recorded_after_the_startup_snapshot_is_not_requeued() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        final var now = System.currentTimeMillis();
        writeRecord("run-old", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), now);
        final var startupRuns = TriggerRunLog.pendingRunIds();
        writeRecord("run-live", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), now);

        TriggerRunRecovery.recoverLocal(startupRuns);
        sleep();

        assertEquals(List.of("run-old"), captured.stream().map(TriggerEvent::getRunId).toList(),
                "a run recorded once the executor is live was already queued by its producer");
        assertEquals(1, TriggerRunLog.recordIdsFor("run-live").size(), "and its record is left to that run");
    }

    @Test
    public void test_an_empty_snapshot_requeues_nothing() throws Exception {
        writeRecord("run-c", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal(Set.of());
        sleep();

        assertTrue(captured.isEmpty());
        assertEquals(1, TriggerRunLog.pending().size());
    }

    @Test
    public void test_deleted_event_replays_the_stored_document() throws Exception {
        writeRecord("run-c", TriggerRunLog.currentNodeId(), EventType.DELETED, List.of(), List.of(document("removed")),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertEquals(1, captured.size());
        assertEquals(EventType.DELETED, captured.getFirst().getType());
        assertEquals("removed", captured.getFirst().getEntries().getFirst().get_id());
    }

    @Test
    public void test_a_recovered_run_fires_with_its_recorded_fired_at() throws Exception {
        final var firedAt = System.currentTimeMillis() - 10_000L;
        writeRecord("run-fired", TriggerRunLog.currentNodeId(), EventType.DELETED, List.of(), List.of(document("gone")),
                firedAt);

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertEquals(1, captured.size());
        assertEquals(firedAt, captured.getFirst().getFiredAt(),
                "a restart must not restart the run's age, which bounds the cluster wait and is shown to scripts");
    }

    @Test
    public void test_a_record_without_fired_at_is_not_treated_as_expired() throws Exception {
        final var before = System.currentTimeMillis();
        writeRecord("run-unstamped", TriggerRunLog.currentNodeId(), EventType.DELETED, List.of(),
                List.of(document("unstamped")), 0L);

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertEquals(1, captured.size());
        assertTrue(captured.getFirst().getFiredAt() >= before,
                "a record written before firedAt was stored must not read as decades old");
    }

    @Test
    public void test_a_run_whose_documents_vanished_is_consumed() throws Exception {
        writeRecord("run-d", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("no-such-doc"), List.of(),
                System.currentTimeMillis());

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertTrue(captured.isEmpty());
        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_recovery_is_skipped_when_triggers_are_disabled() throws Exception {
        writeRecord("run-e", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(),
                System.currentTimeMillis());
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertTrue(captured.isEmpty());
        assertEquals(1, TriggerRunLog.pending().size());
    }

    @Test
    public void test_recovery_is_skipped_when_the_run_log_is_disabled() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);

        assertDoesNotThrow(() -> TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds()));
        assertTrue(captured.isEmpty());
    }

    @Test
    public void test_warns_about_a_long_pending_run() throws Exception {
        writeRecord("run-f", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), 1L);

        assertDoesNotThrow(TriggerRunRecovery::warnAboutStrandedRuns);
        assertEquals(1, TriggerRunLog.pending().size(), "warning must not consume the record");
    }

    @Test
    public void test_warning_is_skipped_when_triggers_are_disabled() throws Exception {
        writeRecord("run-g", TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), 1L);
        TestUtils.setPrivateField(configuration, "triggersEnabled", false);

        assertDoesNotThrow(TriggerRunRecovery::warnAboutStrandedRuns);
    }

    @Test
    public void test_garbage_collect_uses_the_configured_retention() throws Exception {
        writeRecord("run-h", "some-other-node", EventType.UPDATED, List.of("live"), List.of(), 1L);
        assertNotNull(TriggerRunLog.pending());

        TriggerRunRecovery.garbageCollect();

        assertTrue(TriggerRunLog.pending().isEmpty(),
                "another node's record older than triggerRunRetentionMs is stranded and collected");
    }

    @Test
    public void test_a_run_that_cannot_be_rebuilt_does_not_strand_the_runs_after_it() throws Exception {
        saveDocument();
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
        captured.clear();
        final var firedAt = System.currentTimeMillis();
        final var unreadable = new JsonObject();
        unreadable.addProperty(Globals.PK_FIELD, 5);
        writeRecord("run-broken", TriggerRunLog.currentNodeId(), EventType.DELETED, List.of(), List.of(unreadable),
                firedAt - 1);
        final var recoverable = List.of("run-ok-1", "run-ok-2", "run-ok-3", "run-ok-4", "run-ok-5", "run-ok-6");
        for (final var runId : recoverable) {
            writeRecord(runId, TriggerRunLog.currentNodeId(), EventType.UPDATED, List.of("live"), List.of(), firedAt);
        }

        TriggerRunRecovery.recoverLocal(TriggerRunLog.pendingRunIds());
        sleep();

        assertEquals(recoverable, captured.stream().map(TriggerEvent::getRunId).sorted().toList());
        assertTrue(TriggerRunLog.pending().stream().anyMatch(entry -> entry.getRunId().equals("run-broken")),
                "a run that could not be rebuilt stays pending for the next recovery");
    }
}
