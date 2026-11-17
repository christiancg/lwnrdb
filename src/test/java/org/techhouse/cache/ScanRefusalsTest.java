package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.DbEntry;
import org.techhouse.data.admin.AdminPageEntry;
import org.techhouse.ejson.elements.JsonObject;

public class ScanRefusalsTest {
    private static final String DB = "refusalDb";
    private static final String COLL = "refusalColl";
    private static final String ID = DB + Globals.COLL_IDENTIFIER_SEPARATOR + COLL;
    private static final int PAGES = 4;
    private static final int DOCS_PER_PAGE = 20;

    private final ScanRefusals refusals = new ScanRefusals();

    private static final class RecordingCompletion implements ScanAdmission.Completion {
        final List<Long> refusedAt = new ArrayList<>();
        final List<Integer> pagesReadAtRefusal = new ArrayList<>();
        int admitted;
        int[] pagesRead;

        @Override
        public void admit(ConcurrentHashMap<String, DbEntry> collected, long measuredBytes) {
            admitted = collected.size();
        }

        @Override
        public void refuse(long reachedBytes) {
            refusedAt.add(reachedBytes);
            pagesReadAtRefusal.add(pagesRead[0]);
        }
    }

    private static List<AdminPageEntry> pages() {
        final var pages = new ArrayList<AdminPageEntry>();
        for (var page = 0; page < PAGES; page++) {
            final var entry = new AdminPageEntry(DB, COLL, page);
            entry.setPageSize(10_000L);
            entry.setEntryCount(DOCS_PER_PAGE);
            pages.add(entry);
        }
        return pages;
    }

    private static Collection<DbEntry> pageOf(long page) {
        final var documents = new ArrayList<DbEntry>();
        for (var i = 0; i < DOCS_PER_PAGE; i++) {
            final var object = new JsonObject();
            object.addProperty(Globals.PK_FIELD, "p" + page + "-" + i);
            object.addProperty("payload", "value-" + page + "-" + i);
            documents.add(DbEntry.fromJsonObject(DB, COLL, object));
        }
        return documents;
    }

    private static long onePageBytes() {
        return CacheSizeEstimator.estimateCollectionEntries(pageOf(0));
    }

    private static MemoryManagement budgetOf(long freeBytes) {
        final var memory = mock(MemoryManagement.class);
        when(memory.admissionCheck(anyLong())).thenAnswer(
                call -> call.<Long>getArgument(0) > freeBytes ? AdmissionDecision.REJECT : AdmissionDecision.ADMIT);
        return memory;
    }

    private static long scan(MemoryManagement memory, RecordingCompletion completion) {
        return scan(memory, completion, pages());
    }

    private static long scan(MemoryManagement memory, RecordingCompletion completion, List<AdminPageEntry> rows) {
        final var read = new int[1];
        completion.pagesRead = read;
        final var scan = new ScanAdmission(rows, false, page -> {
            read[0]++;
            return pageOf(page);
        }, memory, completion);
        return StreamSupport.stream(scan, false).count();
    }

    @Test
    public void test_a_projected_overflow_abandons_on_the_first_page() {
        final var memory = budgetOf(2 * onePageBytes());
        final var completion = new RecordingCompletion();

        assertEquals(PAGES * DOCS_PER_PAGE, scan(memory, completion), "the scan still answers every document");

        assertEquals(List.of(1), completion.pagesReadAtRefusal);
        assertEquals(List.of(PAGES * onePageBytes()), completion.refusedAt);
        assertEquals(0, completion.admitted);
        verify(memory, times(1)).admissionCheck(anyLong());
    }

    @Test
    public void test_a_collection_that_fits_its_projection_is_collected_and_admitted() {
        final var memory = budgetOf(PAGES * onePageBytes());
        final var completion = new RecordingCompletion();

        assertEquals(PAGES * DOCS_PER_PAGE, scan(memory, completion));

        assertTrue(completion.refusedAt.isEmpty());
        assertEquals(PAGES * DOCS_PER_PAGE, completion.admitted);
        verify(memory, times(PAGES)).admissionCheck(anyLong());
    }

    @Test
    public void test_once_every_live_byte_was_read_the_check_is_exact() {
        final var rows = pages();
        rows.getLast().setPageSize(0L);
        final var memory = budgetOf(PAGES * onePageBytes() - onePageBytes() / 2);
        final var completion = new RecordingCompletion();

        assertEquals(PAGES * DOCS_PER_PAGE, scan(memory, completion, rows));

        assertEquals(List.of(PAGES), completion.pagesReadAtRefusal);
        assertEquals(List.of(PAGES * onePageBytes()), completion.refusedAt);
        assertEquals(0, completion.admitted);
    }

    @Test
    public void test_live_bytes_sum_the_page_rows_and_ignore_negative_ones() {
        final var rows = pages();
        rows.getFirst().setPageSize(-5L);

        assertEquals((PAGES - 1) * 10_000L, ScanAdmission.liveBytesOf(rows));
    }

    @Test
    public void test_no_memo_refuses_nothing() {
        assertFalse(refusals.refusedAt(ID, 0L, 100L));
    }

    @Test
    public void test_a_remembered_refusal_holds_while_the_free_budget_is_below_what_it_reached() {
        refusals.refused(ID, 1_000L, 8_000L);

        assertTrue(refusals.refusedAt(ID, 999L, 8_000L));
        assertTrue(refusals.refusedAt(ID, 0L, 8_000L));
        assertEquals(1, refusals.size());
    }

    @Test
    public void test_a_free_budget_that_reaches_the_refused_size_forgets_the_refusal() {
        refusals.refused(ID, 1_000L, 8_000L);

        assertFalse(refusals.refusedAt(ID, 1_000L, 8_000L));
        assertEquals(0, refusals.size());
        assertFalse(refusals.refusedAt(ID, 0L, 8_000L), "the next scan collects again and may be refused anew");
    }

    @Test
    public void test_live_bytes_changing_by_more_than_an_eighth_forget_the_refusal() {
        refusals.refused(ID, 1_000L, 8_000L);
        assertTrue(refusals.refusedAt(ID, 0L, 7_000L), "exactly an eighth smaller still refuses");
        assertTrue(refusals.refusedAt(ID, 0L, 9_000L), "exactly an eighth larger still refuses");

        assertFalse(refusals.refusedAt(ID, 0L, 6_999L));

        refusals.refused(ID, 1_000L, 8_000L);
        assertFalse(refusals.refusedAt(ID, 0L, 9_001L));
    }

    @Test
    public void test_forget_drops_one_collection_and_forget_database_drops_only_that_database() {
        final var sibling = DB + Globals.COLL_IDENTIFIER_SEPARATOR + "other";
        final var longerDb = DB + "x" + Globals.COLL_IDENTIFIER_SEPARATOR + COLL;
        refusals.refused(ID, 1_000L, 8_000L);
        refusals.refused(sibling, 1_000L, 8_000L);
        refusals.refused(longerDb, 1_000L, 8_000L);

        refusals.forget(ID);
        assertFalse(refusals.refusedAt(ID, 0L, 8_000L));
        assertTrue(refusals.refusedAt(sibling, 0L, 8_000L));

        refusals.forgetDatabase(DB);
        assertFalse(refusals.refusedAt(sibling, 0L, 8_000L));
        assertTrue(refusals.refusedAt(longerDb, 0L, 8_000L));
    }

    @Test
    public void test_the_memo_is_bounded() {
        for (var i = 0; i < ScanRefusals.MAX_REMEMBERED; i++) {
            refusals.refused(DB + Globals.COLL_IDENTIFIER_SEPARATOR + "c" + i, 1_000L, 8_000L);
        }
        assertEquals(ScanRefusals.MAX_REMEMBERED, refusals.size());

        refusals.refused(DB + Globals.COLL_IDENTIFIER_SEPARATOR + "c0", 2_000L, 8_000L);
        assertEquals(ScanRefusals.MAX_REMEMBERED, refusals.size(), "refreshing a known collection keeps the rest");

        refusals.refused(ID, 1_000L, 8_000L);
        assertEquals(1, refusals.size());
        assertTrue(refusals.refusedAt(ID, 0L, 8_000L));
    }
}
