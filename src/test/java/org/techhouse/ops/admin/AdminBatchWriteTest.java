package org.techhouse.ops.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.cache.Cache;
import org.techhouse.concurrency.ResourceLocking;
import org.techhouse.config.Globals;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminTransactionEntry;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.fs.FileSystem;
import org.techhouse.fs.RecordingForcer;
import org.techhouse.ioc.IocContainer;
import org.techhouse.test.TestGlobals;
import org.techhouse.test.TestUtils;

public class AdminBatchWriteTest {
    private static final String COLL = Globals.ADMIN_TRANSACTIONS_COLLECTION_NAME;
    private static final int LARGE_PAYLOAD_CHARS = 1_500_000;

    private final FileSystem fs = IocContainer.get(FileSystem.class);
    private final ResourceLocking locks = IocContainer.get(ResourceLocking.class);
    private final Map<AdminTransactionEntry, byte[]> lines = new IdentityHashMap<>();
    private final RecordingRows rows = new RecordingRows();
    private RecordingForcer recorder;

    private final class RecordingRows implements AdminBatchWrite.PkRows {
        final Map<String, PkIndexEntry> published = new TreeMap<>();
        final List<String> events = new ArrayList<>();

        @Override
        public PkIndexEntry get(String id) {
            return published.get(id);
        }

        @Override
        public void publish(PkIndexEntry row) {
            try {
                events.add("publish " + row.getValue() + " landed=" + fs.getById(row).get_id().equals(row.getValue())
                        + " logged=" + walForces());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            published.put(row.getValue(), row);
        }

        @Override
        public void unpublish(Set<String> ids) {
            events.add("unpublish " + ids);
            ids.forEach(published::remove);
        }
    }

    @BeforeEach
    public void setUp() throws Exception {
        TestUtils.standardInitialSetup();
        recorder = RecordingForcer.install(true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        PageRowWriteBack.flushAll();
        recorder.close();
        TestUtils.releaseAllLocks();
        TestUtils.standardTearDown();
    }

    private long walForces() {
        return recorder.forcedNames().stream().filter(name -> name.equals(COLL + Globals.WAL_EXTENSION)).count();
    }

    private AdminTransactionEntry entry(String txId, long seq, int payloadChars) {
        final var payload = new JsonObject();
        payload.addProperty(Globals.PK_FIELD, "doc-" + seq);
        payload.addProperty("filler", "x".repeat(payloadChars));
        final var entry = new AdminTransactionEntry(txId, "client", seq, AdminTransactionEntry.OP_TYPE_SAVE,
                TestGlobals.DB, TestGlobals.COLL, payload);
        lines.put(entry, entry.encodeLine());
        return entry;
    }

    @Test
    public void test_entries_are_cut_into_chunks_of_the_record_budget() {
        final var entries = List.of(entry("tx", 0, LARGE_PAYLOAD_CHARS), entry("tx", 1, LARGE_PAYLOAD_CHARS),
                entry("tx", 2, LARGE_PAYLOAD_CHARS), entry("tx", 3, 10));

        final var chunks = AdminBatchWrite.chunksOf(entries, lines::get);

        assertEquals(List.of(2, 2), chunks.stream().map(List::size).toList());
        assertEquals(List.of(List.<AdminTransactionEntry>of()),
                AdminBatchWrite.chunksOf(List.<AdminTransactionEntry>of(), lines::get));
    }

    @Test
    public void test_each_chunk_is_one_write_ahead_log_record() throws Exception {
        AdminBatchWrite.write(COLL, rows, List.of(entry("warm", 0, 10)), lines::get, List.of());
        final var entries = List.of(entry("tx", 0, LARGE_PAYLOAD_CHARS), entry("tx", 1, LARGE_PAYLOAD_CHARS),
                entry("tx", 2, LARGE_PAYLOAD_CHARS));
        final var before = walForces();

        AdminBatchWrite.writeInChunks(COLL, rows, entries, lines::get, List.of());

        assertEquals(2, walForces() - before);
        assertEquals(4, rows.published.size());
        assertFalse(locks.isWriteLockedByCurrentThread(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, COLL)));
    }

    @Test
    public void test_a_rewrite_spanning_records_never_deletes_what_an_earlier_record_reinserted() throws Exception {
        final var original = List.of(entry("tx", 0, 10), entry("tx", 1, 10), entry("tx", 2, 10));
        AdminBatchWrite.writeInChunks(COLL, rows, original, lines::get, List.of());
        final var rewrite = List.of(entry("tx", 0, LARGE_PAYLOAD_CHARS), entry("tx", 1, LARGE_PAYLOAD_CHARS),
                entry("tx", 2, LARGE_PAYLOAD_CHARS));
        final var replaced = original.stream().map(AdminTransactionEntry::get_id).toList();
        final var before = walForces();

        AdminBatchWrite.writeInChunks(COLL, rows, rewrite, lines::get, replaced);

        assertEquals(2, walForces() - before);
        assertEquals(Set.copyOf(replaced), rows.published.keySet());
        for (final var entry : rewrite) {
            assertEquals(lines.get(entry).length, rows.published.get(entry.get_id()).getLength());
            assertEquals(entry.get_id(), fs.getById(rows.published.get(entry.get_id())).get_id());
        }
    }

    @Test
    public void test_a_rewrite_spanning_records_still_deletes_the_rows_it_does_not_reinsert() throws Exception {
        final var original = List.of(entry("tx", 0, 10), entry("tx", 1, 10), entry("tx", 2, 10));
        AdminBatchWrite.writeInChunks(COLL, rows, original, lines::get, List.of());
        final var rewrite = List.of(entry("tx", 0, LARGE_PAYLOAD_CHARS), entry("tx", 1, LARGE_PAYLOAD_CHARS),
                entry("tx", 3, LARGE_PAYLOAD_CHARS));
        final var replaced = original.stream().map(AdminTransactionEntry::get_id).toList();
        final var before = walForces();

        AdminBatchWrite.writeInChunks(COLL, rows, rewrite, lines::get, replaced);

        assertEquals(2, walForces() - before);
        assertEquals(Set.of(rewrite.get(0).get_id(), rewrite.get(1).get_id(), rewrite.get(2).get_id()),
                rows.published.keySet());
    }

    @Test
    public void test_rows_are_published_only_after_their_record_landed() throws Exception {
        final var first = entry("tx", 0, 10);
        AdminBatchWrite.write(COLL, rows, List.of(first), lines::get, List.of());
        final var replacement = entry("tx", 0, 20);
        final var second = entry("tx", 1, 10);
        rows.events.clear();
        final var before = walForces();

        AdminBatchWrite.write(COLL, rows, List.of(replacement, second), lines::get, List.of(first.get_id()));

        assertEquals(
                List.of("publish " + first.get_id() + " landed=true logged=" + (before + 1),
                        "publish " + second.get_id() + " landed=true logged=" + (before + 1), "unpublish []"),
                rows.events);
        assertEquals(lines.get(replacement).length, rows.published.get(first.get_id()).getLength());
    }

    @Test
    public void test_a_delete_of_a_published_row_unpublishes_it() throws Exception {
        final var first = entry("tx", 0, 10);
        final var second = entry("tx", 1, 10);
        AdminBatchWrite.write(COLL, rows, List.of(first, second), lines::get, List.of());
        rows.events.clear();

        AdminBatchWrite.write(COLL, rows, List.<AdminTransactionEntry>of(), lines::get,
                List.of(first.get_id(), "absent", first.get_id()));

        assertEquals(List.of("unpublish [" + first.get_id() + "]"), rows.events);
        assertEquals(Set.of(second.get_id()), rows.published.keySet());
    }

    @Test
    public void test_a_batch_with_nothing_to_write_writes_no_record() throws Exception {
        final var before = walForces();

        AdminBatchWrite.write(COLL, rows, List.<AdminTransactionEntry>of(), lines::get, List.of("absent"));

        assertEquals(before, walForces());
        assertTrue(rows.events.isEmpty());
    }

    @Test
    public void test_a_failing_write_publishes_nothing() {
        final var stale = new PkIndexEntry(Globals.ADMIN_DB_NAME, COLL, "stale", 1_000_000L, 10L, 0L);
        rows.published.put("stale", stale);
        final var inserted = entry("tx", 0, 10);
        final var before = walForces();

        assertThrows(IOException.class,
                () -> AdminBatchWrite.write(COLL, rows, List.of(inserted), lines::get, List.of("stale")));

        assertEquals(before, walForces());
        assertTrue(rows.events.isEmpty());
        assertEquals(Set.of("stale"), rows.published.keySet());
        assertFalse(locks.isWriteLockedByCurrentThread(Cache.getCollectionIdentifier(Globals.ADMIN_DB_NAME, COLL)));
    }
}
