package org.techhouse.unit.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.events.EventType;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ops.TriggerDispatcher;
import org.techhouse.ops.TriggerRunLog;
import org.techhouse.ops.TriggerRunRecovery;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

/**
 * The durable pending-run log on its own: what it writes, how it chunks, and when it declines to write.
 */
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

    private static TriggerRunLog.TriggerRunDescriptor descriptor(EventType type, List<DbEntry> entries) {
        return new TriggerRunLog.TriggerRunDescriptor(TestGlobals.DB, TestGlobals.COLL, "audit", "recalc", type, false,
                "alice", 0, System.currentTimeMillis(), entries);
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

    // A run whose id list would not fit in one record is split across chunks that share the run id, so a
    // bulk write of many documents is still recoverable.
    @Test
    public void test_large_id_list_is_chunked() throws Exception {
        final var entries = new ArrayList<DbEntry>();
        for (var i = 0; i < 40000; i++) {
            entries.add(entry("id-that-is-reasonably-long-" + i, 0));
        }

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

    // A single deleted document too large to store cannot be logged. The write has already committed, so the
    // run proceeds without a record rather than failing.
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

    @Test
    public void test_garbage_collect_drops_records_past_retention() throws Exception {
        assertNotNull(TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1)))));

        TriggerRunLog.garbageCollect(-1L);

        assertTrue(TriggerRunLog.pending().isEmpty());
    }

    @Test
    public void test_garbage_collect_via_recovery_respects_the_disabled_log() throws Exception {
        assertNotNull(TriggerRunLog.record(descriptor(EventType.CREATED, List.of(entry("a", 1)))));
        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", false);

        TriggerRunRecovery.garbageCollect();

        TestUtils.setPrivateField(configuration, "triggerRunLogEnabled", true);
        assertEquals(1, TriggerRunLog.pending().size(), "a disabled log must not collect anything");
    }

    // A standalone node has no cluster identity, so it uses a fixed id that is stable across restarts.
    @Test
    public void test_standalone_node_id_is_stable() {
        assertEquals(TriggerRunLog.currentNodeId(), TriggerRunLog.currentNodeId());
        assertNotNull(TriggerRunLog.currentNodeId());
    }

    @Test
    public void test_record_ids_for_an_unknown_run_is_empty() {
        assertTrue(TriggerRunLog.recordIdsFor("no-such-run").isEmpty());
    }
}
