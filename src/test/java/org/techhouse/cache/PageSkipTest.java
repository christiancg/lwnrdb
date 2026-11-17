package org.techhouse.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.data.admin.AdminPageEntry;

public class PageSkipTest {
    private static final String DB = "skipDb";
    private static final String COLL = "skipColl";

    private static List<AdminPageEntry> pagesWithCounts(int... liveRows) {
        final var pages = new ArrayList<AdminPageEntry>();
        for (var i = 0; i < liveRows.length; i++) {
            final var page = new AdminPageEntry(DB, COLL, i + 1L);
            page.setEntryCount(liveRows[i]);
            pages.add(page);
        }
        return pages;
    }

    private static List<Long> pageNumbers(PageSkip.Plan plan) {
        return plan.pages().stream().map(AdminPageEntry::getPage).toList();
    }

    @Test
    public void test_a_skip_inside_the_first_page_keeps_every_page() {
        final var plan = PageSkip.plan(pagesWithCounts(3, 2, 1), 2);
        assertNotNull(plan);

        assertEquals(List.of(1L, 2L, 3L), pageNumbers(plan));
        assertEquals(2, plan.remainder());
    }

    @Test
    public void test_a_skip_on_a_page_boundary_drops_the_whole_page() {
        final var pages = pagesWithCounts(3, 2, 1);
        assertTrue(PageSkip.countsMatch(pages, 6));
        final var plan = PageSkip.plan(pages, 3);
        assertNotNull(plan);

        assertEquals(List.of(2L, 3L), pageNumbers(plan));
        assertEquals(0, plan.remainder());
    }

    @Test
    public void test_a_skip_past_the_end_leaves_no_page() {
        final var plan = PageSkip.plan(pagesWithCounts(1, 2), 10);
        assertNotNull(plan);

        assertEquals(List.of(), pageNumbers(plan));
        assertEquals(7, plan.remainder());
    }

    @Test
    public void test_an_empty_page_is_dropped_with_the_pages_before_it() {
        final var plan = PageSkip.plan(pagesWithCounts(2, 0, 2), 2);
        assertNotNull(plan);

        assertEquals(List.of(3L), pageNumbers(plan));
        assertEquals(0, plan.remainder());
    }

    @Test
    public void test_a_zero_skip_keeps_every_page_even_an_empty_first_one() {
        final var plan = PageSkip.plan(pagesWithCounts(0, 2), 0);
        assertNotNull(plan);

        assertEquals(List.of(2L), pageNumbers(plan));
        assertEquals(0, plan.remainder());
    }

    @Test
    public void test_counts_that_disagree_with_the_trusted_pk_count_decline() {
        assertFalse(PageSkip.countsMatch(pagesWithCounts(3, 2), 4));
    }

    @Test
    public void test_a_negative_count_before_the_first_kept_page_declines() {
        assertNull(PageSkip.plan(pagesWithCounts(2, -1, 3), 3));
    }
}
