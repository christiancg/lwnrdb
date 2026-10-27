package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.data.PkIndexEntry;
import org.techhouse.data.admin.AdminPageEntry;

public class PageSkipTest {
    private static final String DB = "skipDb";
    private static final String COLL = "skipColl";

    private static List<AdminPageEntry> pages(long... numbers) {
        final var pages = new ArrayList<AdminPageEntry>();
        for (final var number : numbers) {
            pages.add(new AdminPageEntry(DB, COLL, number));
        }
        return pages;
    }

    private static List<PkIndexEntry> rows(long... pagePerRow) {
        final var rows = new ArrayList<PkIndexEntry>();
        for (var i = 0; i < pagePerRow.length; i++) {
            rows.add(new PkIndexEntry(DB, COLL, "id" + i, 0, 10, pagePerRow[i]));
        }
        return rows;
    }

    private static List<Long> pageNumbers(PageSkip.Plan plan) {
        return plan.pages().stream().map(AdminPageEntry::getPage).toList();
    }

    @Test
    public void test_a_skip_inside_the_first_page_keeps_every_page() {
        final var plan = PageSkip.plan(pages(1, 2, 3), rows(1, 2, 1, 3, 1, 2), 2);

        assertEquals(List.of(1L, 2L, 3L), pageNumbers(plan));
        assertEquals(2, plan.remainder());
    }

    @Test
    public void test_a_skip_on_a_page_boundary_drops_the_whole_page() {
        final var plan = PageSkip.plan(pages(1, 2, 3), rows(1, 2, 1, 3, 1, 2), 3);

        assertEquals(List.of(2L, 3L), pageNumbers(plan));
        assertEquals(0, plan.remainder());
    }

    @Test
    public void test_a_skip_past_the_end_leaves_no_page() {
        final var plan = PageSkip.plan(pages(1, 2), rows(1, 2, 2), 10);

        assertEquals(List.of(), pageNumbers(plan));
        assertEquals(7, plan.remainder());
    }

    @Test
    public void test_an_empty_page_is_dropped_with_the_pages_before_it() {
        final var plan = PageSkip.plan(pages(1, 2, 3), rows(1, 1, 3, 3), 2);

        assertEquals(List.of(3L), pageNumbers(plan));
        assertEquals(0, plan.remainder());
    }

    @Test
    public void test_a_row_on_a_page_the_scan_never_reads_is_ignored() {
        final var plan = PageSkip.plan(pages(1, 3), rows(1, 2, 2, 3, 3), 1);

        assertEquals(List.of(3L), pageNumbers(plan));
        assertEquals(0, plan.remainder());
    }

    @Test
    public void test_a_zero_skip_keeps_every_page_even_an_empty_first_one() {
        final var plan = PageSkip.plan(pages(1, 2), rows(2, 2), 0);

        assertEquals(List.of(2L), pageNumbers(plan));
        assertEquals(0, plan.remainder());
    }
}
