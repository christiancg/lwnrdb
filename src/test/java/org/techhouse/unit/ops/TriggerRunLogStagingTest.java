package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class TriggerRunLogStagingTest {
    private static final Configuration configuration = Configuration.getInstance();
    private static final String TX_ID = "4f9d7a0e-3c1b-4e7a-9a55-0d2f6b1c8e11";

    @BeforeAll
    static void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        TestUtils.createTestDatabaseAndCollection();
    }

    @AfterAll
    static void tearDown() throws Exception {
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

    private static DbEntry idEntry(String id) {
        final var data = new JsonObject();
        data.add(Globals.PK_FIELD, new JsonString(id));
        return DbEntry.fromJsonObject(TestGlobals.DB, TestGlobals.COLL, data);
    }

    private static TriggerRunLog.TriggerRunDescriptor descriptor(EventType type, List<DbEntry> entries) {
        return new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit", "recalc", type, true,
                "alice", 0, System.currentTimeMillis(), entries);
    }

    private static List<DbEntry> entriesSpanningSeveralChunks() {
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 40000; i++) {
            entries.add(idEntry("id-that-is-reasonably-long-" + i));
        }
        return entries;
    }

    private static Map<String, Long> versionsOf(List<DbEntry> entries, long version) {
        final var versions = new HashMap<String, Long>();
        entries.forEach(entry -> versions.put(entry.get_id(), version));
        return versions;
    }

    @Test
    public void test_record_staged_writes_staged_chunks_with_prior_versions() throws Exception {
        final var runId = TriggerRunLog.recordStaged(descriptor(EventType.UPDATED, List.of(idEntry("a"))),
                Map.of("a", 42L));

        assertNotNull(runId);
        final var pending = TriggerRunLog.pending();
        assertEquals(1, pending.size());
        assertEquals(TriggerRunStatus.STAGED, pending.getFirst().getStatus());
        assertEquals(Map.of("a", 42L), pending.getFirst().getPriorVersions());
    }

    @Test
    public void test_a_staged_chunked_run_keeps_every_chunk_within_max_entry_size() throws Exception {
        final var entries = entriesSpanningSeveralChunks();

        final var runId = TriggerRunLog.recordStaged(descriptor(EventType.CREATED, entries),
                versionsOf(entries, AdminTriggerRunEntry.ABSENT_VERSION));

        assertNotNull(runId);
        var versioned = 0;
        for (final var chunk : TriggerRunLog.pending()) {
            assertTrue(chunk.byteSize() <= configuration.getMaxEntrySize(), "every chunk must fit maxEntrySize");
            assertEquals(chunk.getIds().size(), chunk.getPriorVersions().size(), "a chunk versions its own ids");
            versioned += chunk.getPriorVersions().size();
        }
        assertEquals(entries.size(), versioned);
    }

    @Test
    public void test_record_deterministic_replaces_existing_chunks_of_that_run() throws Exception {
        final var runId = TriggerRunLog.deterministicRunId(TX_ID, "audit", EventType.CREATED, null);
        TriggerRunLog.recordDeterministic(descriptor(EventType.CREATED, entriesSpanningSeveralChunks()), runId);
        assertTrue(TriggerRunLog.recordIdsFor(runId).size() > 1);

        assertEquals(runId,
                TriggerRunLog.recordDeterministic(descriptor(EventType.CREATED, List.of(idEntry("a"))), runId));

        assertEquals(1, TriggerRunLog.recordIdsFor(runId).size(), "a second stage must replace, not duplicate");
        assertEquals(List.of("a"), TriggerRunLog.pending().getFirst().getIds());
        assertEquals(TriggerRunStatus.PENDING, TriggerRunLog.pending().getFirst().getStatus());
    }

    @Test
    public void test_deterministic_run_id_is_stable_and_distinct_per_trigger_type_and_id() {
        final var runId = TriggerRunLog.deterministicRunId(TX_ID, "audit", EventType.CREATED, "a");

        assertEquals(runId, TriggerRunLog.deterministicRunId(TX_ID, "audit", EventType.CREATED, "a"));
        assertNotEquals(runId, TriggerRunLog.deterministicRunId(TX_ID, "audit", EventType.CREATED, "b"));
        assertNotEquals(runId, TriggerRunLog.deterministicRunId(TX_ID, "audit", EventType.UPDATED, "a"));
        assertNotEquals(runId, TriggerRunLog.deterministicRunId(TX_ID, "other", EventType.CREATED, "a"));
        assertNotEquals(runId, TriggerRunLog.deterministicRunId(TX_ID, "audit", EventType.CREATED, null));
    }

    @Test
    public void test_confirm_staged_narrows_and_deletes_emptied_chunks() throws Exception {
        final var entries = entriesSpanningSeveralChunks();
        final var runId = TriggerRunLog.recordStaged(descriptor(EventType.UPDATED, entries), versionsOf(entries, 9L));
        assertTrue(TriggerRunLog.recordIdsFor(runId).size() > 1);

        final var remaining = TriggerRunLog.confirmStaged(runId, Set.of(entries.getFirst().get_id()));

        assertEquals(1, remaining.size());
        assertEquals(1, TriggerRunLog.recordIdsFor(runId).size(), "chunks left with no landed id are deleted");
        final var confirmed = TriggerRunLog.pending().getFirst();
        assertEquals(List.of(entries.getFirst().get_id()), confirmed.getIds());
        assertEquals(TriggerRunStatus.PENDING, confirmed.getStatus());
        assertTrue(confirmed.getPriorVersions().isEmpty());
    }

    @Test
    public void test_discard_deletes_every_chunk_and_ignores_a_missing_run() throws Exception {
        final var runId = TriggerRunLog.recordStaged(descriptor(EventType.CREATED, List.of(idEntry("a"))),
                Map.of("a", AdminTriggerRunEntry.ABSENT_VERSION));

        TriggerRunLog.discard(runId);
        TriggerRunLog.discard(null);
        TriggerRunLog.discard("no-such-run");

        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_a_dead_letter_whose_error_looks_like_a_custom_type_stays_readable() throws Exception {
        final var runId = TriggerRunLog.record(descriptor(EventType.CREATED, List.of(idEntry("a"))));

        TriggerRunLog.markAttempt(runId, TriggerRunStatus.DEAD, 1, "#abc(: x)", 0L);

        final var pending = assertDoesNotThrow(TriggerRunLog::pending,
                "one unreadable record used to fail every trigger-run read");
        assertEquals(1, pending.size());
        assertEquals("\\#abc(: x)", pending.getFirst().getLastError());
        assertDoesNotThrow(() -> TriggerRunLog.garbageCollect(-1L));
        assertTrue(TriggerRunLog.pending().isEmpty());
    }
}
