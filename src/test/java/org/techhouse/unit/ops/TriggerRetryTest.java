package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Configuration;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunResolution;
import org.techhouse.ops.req.ResolveTriggerRunRequest;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The retry/dead-letter state machine as it is recorded, and the operator surface over it. The dispatcher's
 * own decisions are exercised end-to-end in {@code TriggerExactlyOnceTest}; this covers the record and the
 * resolution.
 */
public class TriggerRetryTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void reset() throws Exception {
        TestUtils.setPrivateField(configuration, "triggersEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.setPrivateField(configuration, "triggerMaxAttempts", 3);
        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 1_000L);
        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 60_000L);
        TestUtils.setPrivateField(configuration, "triggerRunRetentionMs", 86_400_000L);
        TestUtils.setPrivateField(configuration, "triggerDeadLetterRetentionMs", 604_800_000L);
        TestUtils.setPrivateField(configuration, "maxEntrySize", 1_048_576L);
        for (final var entry : TriggerRunLog.pending()) {
            org.techhouse.ops.TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    private static String recordRun(String triggerName) {
        return recordRun(triggerName, EventType.CREATED);
    }

    // A DELETED run stores the documents inline, so it is replayable without the collection still holding
    // them - which is what a replay test needs, the ids of a CREATED run being re-read at dispatch time.
    private static String recordRun(String triggerName, EventType eventType) {
        final var data = new JsonObject();
        data.add("_id", new JsonString("doc1"));
        final var entry = new DbEntry();
        entry.setDatabaseName(TestGlobals.DB);
        entry.setCollectionName(TestGlobals.COLL);
        entry.set_id("doc1");
        entry.setData(data);
        return TriggerRunLog.record(new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL,
                triggerName, "proc", eventType, false, "alice", 0, System.currentTimeMillis(), List.of(entry)));
    }

    private static AdminTriggerRunEntry firstChunk(String runId) throws Exception {
        for (final var entry : TriggerRunLog.pending()) {
            if (runId.equals(entry.getRunId())) {
                return entry;
            }
        }
        return null;
    }

    @Test
    public void test_a_fresh_record_reads_as_a_pending_first_attempt() throws Exception {
        final var runId = recordRun("t1");
        final var entry = firstChunk(runId);
        assertNotNull(entry);
        assertEquals(TriggerRunStatus.PENDING, entry.getStatus());
        assertEquals(0, entry.getAttempts());
        assertNull(entry.getLastError());
    }

    @Test
    public void test_mark_attempt_round_trips_through_the_record() throws Exception {
        final var runId = recordRun("t2");
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.PENDING, 1, "Error: nope", 1_700_000_000_000L);

        final var entry = firstChunk(runId);
        assertNotNull(entry);
        assertEquals(TriggerRunStatus.PENDING, entry.getStatus());
        assertEquals(1, entry.getAttempts());
        assertEquals("Error: nope", entry.getLastError());
        assertEquals(1_700_000_000_000L, entry.getNextAttemptAt());
        assertTrue(entry.getLastErrorAt() > 0L);
    }

    @Test
    public void test_marking_dead_keeps_the_payload_so_the_run_stays_replayable() throws Exception {
        final var runId = recordRun("t3");
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 3, "Error: nope", 0L);

        final var entry = firstChunk(runId);
        assertNotNull(entry);
        assertEquals(TriggerRunStatus.DEAD, entry.getStatus());
        assertEquals(List.of("doc1"), entry.getIds());
    }

    // A dead letter is waiting for a human, so the pending clock must not sweep it away first.
    @Test
    public void test_the_two_retentions_apply_to_the_two_states() throws Exception {
        final var pendingRun = recordRun("t4");
        final var deadRun = recordRun("t5");
        TriggerRunLog.markAttempt(deadRun, TriggerRunStatus.DEAD, 3, "Error: nope", 0L);

        TriggerRunLog.garbageCollect(0L, 3_600_000L);

        assertNull(firstChunk(pendingRun), "a pending run past its retention is swept");
        assertNotNull(firstChunk(deadRun), "a dead letter keeps its own, longer retention");
    }

    @Test
    public void test_local_rows_report_each_run_once_and_honour_the_filter() {
        final var pendingRun = recordRun("t6");
        final var deadRun = recordRun("t7");
        TriggerRunLog.markAttempt(deadRun, TriggerRunStatus.DEAD, 2, "Error: nope", 0L);

        final var all = TriggerRunResolution.localRows(null);
        assertEquals(2, all.size());

        final var dead = TriggerRunResolution.localRows(TriggerRunStatus.DEAD);
        assertEquals(1, dead.size());
        assertEquals(deadRun, dead.getFirst().getRunId());
        assertEquals("Error: nope", dead.getFirst().getLastError());
        assertEquals(2, dead.getFirst().getAttempts());
        assertEquals(TestGlobals.COLL, dead.getFirst().getCollection());

        assertEquals(1, TriggerRunResolution.localRows(TriggerRunStatus.PENDING).size());
        assertEquals(pendingRun, TriggerRunResolution.localRows(TriggerRunStatus.PENDING).getFirst().getRunId());
    }

    @Test
    public void test_discard_removes_the_record() throws Exception {
        final var runId = recordRun("t8");
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 3, "Error: nope", 0L);

        assertTrue(TriggerRunResolution.resolveLocal(runId, ResolveTriggerRunRequest.DECISION_DISCARD));
        assertNull(firstChunk(runId));
    }

    // The operator has decided the cause is fixed, so the exhausted count must not dead-letter it again on
    // the first failure.
    @Test
    public void test_replay_resets_the_attempt_count_and_status() throws Exception {
        final var runId = recordRun("t9", EventType.DELETED);
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 3, "Error: nope", 0L);

        assertTrue(TriggerRunResolution.resolveLocal(runId, ResolveTriggerRunRequest.DECISION_REPLAY));

        final var entry = firstChunk(runId);
        assertNotNull(entry, "a replayed run keeps its record until the replay applies it");
        assertEquals(TriggerRunStatus.PENDING, entry.getStatus());
        assertEquals(0, entry.getAttempts());
        assertNull(entry.getLastError());
    }

    @Test
    public void test_resolving_an_unknown_run_reports_false() {
        assertFalse(TriggerRunResolution.resolveLocal("no-such-run", ResolveTriggerRunRequest.DECISION_DISCARD));
        assertFalse(TriggerRunResolution.resolveLocal(null, ResolveTriggerRunRequest.DECISION_REPLAY));
    }

    @Test
    public void test_backoff_doubles_and_is_clamped() throws Exception {
        assertEquals(1_000L, org.techhouse.ops.TriggerDispatcher.backoffFor(1));
        assertEquals(2_000L, org.techhouse.ops.TriggerDispatcher.backoffFor(2));
        assertEquals(4_000L, org.techhouse.ops.TriggerDispatcher.backoffFor(3));

        TestUtils.setPrivateField(configuration, "triggerRetryMaxBackoffMs", 3_000L);
        assertEquals(3_000L, org.techhouse.ops.TriggerDispatcher.backoffFor(3));

        TestUtils.setPrivateField(configuration, "triggerRetryBackoffMs", 0L);
        assertEquals(0L, org.techhouse.ops.TriggerDispatcher.backoffFor(1));
    }

    // The wire surface over the same state: the operations an operator actually issues.
    @Test
    public void test_list_trigger_runs_operation_reports_the_rows() {
        final var runId = recordRun("t10");
        final var response = new org.techhouse.ops.OperationProcessor()
                .processMessage(new org.techhouse.ops.req.ListTriggerRunsRequest());
        assertEquals(org.techhouse.ops.OperationStatus.OK, response.getStatus());
        final var runs = ((org.techhouse.ops.resp.ListTriggerRunsResponse) response).getRuns();
        assertEquals(1, runs.size());
        assertEquals(runId, runs.getFirst().get("runId").asJsonString().getValue());
        assertEquals("PENDING", runs.getFirst().get("status").asJsonString().getValue());
    }

    @Test
    public void test_list_trigger_runs_operation_honours_the_status_filter() {
        recordRun("t11");
        final var deadRun = recordRun("t12");
        TriggerRunLog.markAttempt(deadRun, TriggerRunStatus.DEAD, 3, "Error: nope", 0L);

        final var request = new org.techhouse.ops.req.ListTriggerRunsRequest();
        request.setStatus("dead");
        final var response = (org.techhouse.ops.resp.ListTriggerRunsResponse) new org.techhouse.ops.OperationProcessor()
                .processMessage(request);

        assertEquals(1, response.getRuns().size());
        assertEquals(deadRun, response.getRuns().getFirst().get("runId").asJsonString().getValue());
    }

    @Test
    public void test_resolve_trigger_run_operation_discards_the_record() throws Exception {
        final var runId = recordRun("t13");
        final var request = new ResolveTriggerRunRequest();
        request.setRunId(runId);
        request.setDecision(ResolveTriggerRunRequest.DECISION_DISCARD);

        final var response = (org.techhouse.ops.resp.ResolveTriggerRunResponse) new org.techhouse.ops.OperationProcessor()
                .processMessage(request);

        assertTrue(response.isResolved());
        assertNull(firstChunk(runId));
    }

    // An id nobody holds is not an error: the operator asked every member and none answered - the
    // treatment CANCEL_SCRIPT gives an already-finished run.
    @Test
    public void test_resolve_trigger_run_operation_reports_an_unknown_run_as_unresolved() {
        final var request = new ResolveTriggerRunRequest();
        request.setRunId("no-such-run");
        request.setDecision(ResolveTriggerRunRequest.DECISION_DISCARD);

        final var response = (org.techhouse.ops.resp.ResolveTriggerRunResponse) new org.techhouse.ops.OperationProcessor()
                .processMessage(request);

        assertEquals(org.techhouse.ops.OperationStatus.OK, response.getStatus());
        assertFalse(response.isResolved());
    }

    // With the run log off there are no records to report or act on, and neither surface may pretend
    // otherwise.
    @Test
    public void test_the_operator_surface_is_empty_when_the_run_log_is_disabled() throws Exception {
        final var runId = recordRun("t14");
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);

        assertTrue(TriggerRunResolution.localRows(null).isEmpty());
        assertFalse(TriggerRunResolution.resolveLocal(runId, ResolveTriggerRunRequest.DECISION_DISCARD));

        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        assertNotNull(firstChunk(runId), "the record itself is untouched");
    }

    // A CREATED run replays by re-reading its documents, so a run whose documents are gone can never apply
    // and is discarded rather than left for an operator to retry forever.
    @Test
    public void test_replaying_a_run_whose_documents_are_gone_discards_it() throws Exception {
        final var runId = recordRun("t15", EventType.CREATED);
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 3, "Error: nope", 0L);

        assertTrue(TriggerRunResolution.resolveLocal(runId, ResolveTriggerRunRequest.DECISION_REPLAY));
        assertNull(firstChunk(runId));
    }

    // Reached from a peer as well as from the validated wire request, so an unrecognised decision must be
    // refused rather than silently replaying.
    @Test
    public void test_an_unknown_decision_is_refused() throws Exception {
        final var runId = recordRun("t16");
        assertFalse(TriggerRunResolution.resolveLocal(runId, "sideways"));
        assertNotNull(firstChunk(runId));
    }
}
