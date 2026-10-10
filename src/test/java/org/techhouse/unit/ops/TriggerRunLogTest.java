package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminTriggerRunEntry;
import org.techhouse.data.admin.TriggerRunStatus;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerRunLogTest {
    private static final Configuration configuration = Configuration.getInstance();

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    @BeforeEach
    void clear() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        for (final var entry : TriggerRunLog.pending()) {
            TriggerDispatcher.consumeQuietly(entry.getRunId(), entry.getTriggerName());
        }
    }

    private static DbEntry entry(String id, int payloadChars) {
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString(id));
        data.add("blob", new JsonString("x".repeat(payloadChars)));
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
    }

    private static final long THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

    private static TriggerRunLog.TriggerRunDescriptor descriptor(EventType type, List<DbEntry> entries) {
        return firedAt(System.currentTimeMillis(), type, entries);
    }

    private static TriggerRunLog.TriggerRunDescriptor firedAt(long firedAt, EventType type, List<DbEntry> entries) {
        return new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit", "recalc", type, false,
                "alice", 0, firedAt, entries);
    }

    private static String deadLetter(long firedAt, String error) {
        final var runId = TriggerRunLog.record(firedAt(firedAt, EventType.CREATED, List.of(entry("a", 1))));
        assertNotNull(runId);
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 1, error, 0L);
        return runId;
    }

    @Test
    public void test_mark_attempt_updates_rather_than_duplicates() throws Exception {
        final var runId = TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1))));
        assertNotNull(runId);
        final var recordIds = TriggerRunLog.recordIdsFor(runId);

        for (var attempt = 1; attempt <= 3; attempt++) {
            TriggerRunLog.markAttempt(runId, org.techhouse.data.admin.TriggerRunStatus.PENDING, attempt, "boom",
                    System.currentTimeMillis());
        }

        assertEquals(recordIds.size(), TriggerRunLog.recordIdsFor(runId).size(),
                "a retry must update the run record in place, not append another physical copy of it");
        final var pending = TriggerRunLog.pending();
        assertEquals(1, pending.size(), "one run must stay one row however many times it is retried");
        assertEquals(3, pending.getFirst().getAttempts());
    }

    @Test
    public void test_records_a_single_chunk_for_a_small_run() throws Exception {
        final var runId = TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1))));

        assertNotNull(runId);
        assertEquals(1, TriggerRunLog.recordIdsFor(runId).size());
        final var pending = TriggerRunLog.pending();
        assertEquals(1, pending.size());
        assertEquals(List.of("a"), pending.getFirst().getIds());
    }

    private static List<DbEntry> entriesSpanningSeveralChunks() {
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 40000; i++) {
            entries.add(entry("id-that-is-reasonably-long-" + i, 0));
        }
        return entries;
    }

    @Test
    public void test_large_id_list_is_chunked() throws Exception {
        final var entries = entriesSpanningSeveralChunks();

        final var runId = TriggerRunLog.record(descriptor(EventType.CREATED, entries));

        assertNotNull(runId);
        final var chunks = TriggerRunLog.recordIdsFor(runId);
        assertTrue(chunks.size() > 1, "expected the ids to be split across chunks, got " + chunks.size());
        var total = 0;
        for (final var record : TriggerRunLog.pending()) {
            total += record.getIds().size();
            assertTrue(record.byteSize() <= configuration.getMaxEntrySize(),
                    "every chunk must fit within maxEntrySize");
        }
        assertEquals(entries.size(), total, "chunking must not drop ids");
    }

    @Test
    public void test_oversized_deleted_document_falls_back_to_non_durable() {
        final var huge = entry("huge", (int) configuration.getMaxEntrySize());

        final var runId = TriggerRunLog.record(descriptor(EventType.DELETED, List.of(huge)));

        assertNull(runId);
    }

    @Test
    public void test_deleted_run_stores_the_documents() throws Exception {
        final var runId = TriggerRunLog.record(descriptor(EventType.DELETED, List.of(entry("gone", 1))));

        assertNotNull(runId);
        final var pending = TriggerRunLog.pending();
        assertEquals(1, pending.size());
        assertEquals(1, pending.getFirst().getDocuments().size());
        assertTrue(pending.getFirst().getIds().isEmpty());
    }

    @Test
    public void test_disabled_log_records_nothing() throws Exception {
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);

        assertNull(TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1)))));
        assertTrue(TriggerRunLog.pending().isEmpty());
        assertFalse(TriggerRunLog.isEnabled());
    }

    @Test
    public void test_garbage_collect_keeps_recent_records() throws Exception {
        final var runId = TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1))));
        assertNotNull(runId);

        TriggerRunLog.garbageCollect(60_000L);

        assertEquals(1, TriggerRunLog.pending().size());
    }

    private static String recordedByAnotherNode() {
        try (var runLog = mockStatic(TriggerRunLog.class, CALLS_REAL_METHODS)) {
            runLog.when(TriggerRunLog::currentNodeId).thenReturn("another-node");
            return TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1))));
        }
    }

    @Test
    public void test_garbage_collect_drops_another_nodes_records_past_retention() throws Exception {
        assertNotNull(recordedByAnotherNode());

        TriggerRunLog.garbageCollect(-1L);

        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_garbage_collect_keeps_this_nodes_pending_runs_past_retention() throws Exception {
        assertNotNull(TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1)))));

        TriggerRunLog.garbageCollect(-1L);

        assertEquals(1, TriggerRunLog.pending().size(),
                "a run this node recorded is replayed by its own recovery, so it is never stranded");
    }

    @Test
    public void test_garbage_collect_keeps_a_standalone_era_run_once_clustered() throws Exception {
        assertNotNull(TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1)))));
        TestUtils.setPrivateField(configuration, "clusterEnabled", true);
        try {
            TriggerRunLog.garbageCollect(-1L);
        } finally {
            TestUtils.setPrivateField(configuration, "clusterEnabled", false);
        }

        assertEquals(1, TriggerRunLog.pending().size(),
                "a run stamped local was recorded by this node, so switching the cluster on does not strand it");
    }

    @Test
    public void test_garbage_collect_keeps_this_nodes_staged_runs_past_retention() throws Exception {
        assertNotNull(TriggerRunLog.recordStaged(descriptor(EventType.CREATED, List.of(entry("a", 1))),
                Map.of("a", AdminTriggerRunEntry.ABSENT_VERSION)));

        TriggerRunLog.garbageCollect(-1L);

        assertEquals(1, TriggerRunLog.pending().size());
    }

    @Test
    public void test_garbage_collect_still_drops_this_nodes_dead_letters_past_their_retention() throws Exception {
        final var runId = TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1))));
        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 1, "boom", System.currentTimeMillis());

        TriggerRunLog.garbageCollect(60_000L, -1L);

        assertTrue(TriggerRunLog.pending().isEmpty(), "a dead letter keeps its own clock whichever node owns it");
    }

    @Test
    public void test_garbage_collect_via_recovery_respects_the_disabled_log() throws Exception {
        assertNotNull(TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1)))));
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);

        TriggerRunRecovery.garbageCollect();

        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        assertEquals(1, TriggerRunLog.pending().size(), "a disabled log must not collect anything");
    }

    @Test
    public void test_standalone_node_id_is_stable() {
        assertEquals(TriggerRunLog.currentNodeId(), TriggerRunLog.currentNodeId());
        assertNotNull(TriggerRunLog.currentNodeId());
    }

    @Test
    public void test_record_ids_for_an_unknown_run_is_empty() {
        assertTrue(TriggerRunLog.recordIdsFor("no-such-run").isEmpty());
    }

    @Test
    public void test_pending_run_ids_names_a_chunked_run_once() {
        final var chunked = Objects
                .requireNonNull(TriggerRunLog.record(descriptor(EventType.CREATED, entriesSpanningSeveralChunks())));
        final var single = Objects
                .requireNonNull(TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1)))));
        assertTrue(TriggerRunLog.recordIdsFor(chunked).size() > 1);

        assertEquals(Set.of(chunked, single), TriggerRunLog.pendingRunIds());
    }

    @Test
    public void test_pending_run_ids_is_empty_without_records() {
        assertTrue(TriggerRunLog.pendingRunIds().isEmpty());
    }

    @Test
    public void test_a_dead_letter_that_died_recently_is_kept_however_long_ago_it_fired() throws Exception {
        deadLetter(System.currentTimeMillis() - THIRTY_DAYS_MS, "boom");

        TriggerRunLog.garbageCollect(60_000L, SEVEN_DAYS_MS);

        assertEquals(1, TriggerRunLog.pending().size(),
                "a run replayed after a long outage that then dies must stay visible for the operator");
    }

    @Test
    public void test_a_dead_letter_without_a_recorded_death_falls_back_to_when_it_fired() throws Exception {
        deadLetter(System.currentTimeMillis() - THIRTY_DAYS_MS, null);
        final var recent = deadLetter(System.currentTimeMillis(), null);

        TriggerRunLog.garbageCollect(60_000L, SEVEN_DAYS_MS);

        final var pending = TriggerRunLog.pending();
        assertEquals(1, pending.size());
        assertEquals(recent, pending.getFirst().getRunId());
    }
}
