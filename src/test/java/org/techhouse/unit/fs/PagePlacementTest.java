package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.fs.PagePlacement;

public class PagePlacementTest {
    private static final long MAX = 1000;

    @Test
    public void anUpdateAppendsWhileTheFileHasRoom() {
        assertEquals(PagePlacement.APPEND, PagePlacement.forUpdate(900, 900, 100, MAX));
        assertEquals(PagePlacement.APPEND, PagePlacement.forUpdate(999, 999, 1, MAX));
    }

    @Test
    public void anUpdateVacuumsWhenAQuarterPageIsDeadAndTheKeptOldCopyStillLeavesRoom() {
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forUpdate(650, 950, 100, MAX));
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forUpdate(100, 1000, 1, MAX));
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forUpdate(700, 1000, 300, MAX));
    }

    @Test
    public void anUpdateRelocatesWhenLessThanAQuarterPageIsDead() {
        assertEquals(PagePlacement.RELOCATE, PagePlacement.forUpdate(751, 950, 100, MAX));
        assertEquals(PagePlacement.RELOCATE, PagePlacement.forUpdate(1100, 1000, 200, MAX));
    }

    @Test
    public void test_an_update_relocates_when_the_live_bytes_and_the_new_line_overflow_the_page() {
        assertEquals(PagePlacement.RELOCATE, PagePlacement.forUpdate(700, 1000, 301, MAX),
                "a vacuum keeps the old copy, so the page would end past the cap");
        final var twoMb = 2L * 1024 * 1024;
        assertEquals(PagePlacement.RELOCATE, PagePlacement.forUpdate(1_400_000, 1_400_000, 700_000, twoMb));
        assertEquals(PagePlacement.RELOCATE, PagePlacement.forUpdate(1_400_000, 2_000_000, 700_000, twoMb));
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forUpdate(700_000, 1_400_000, 700_000, twoMb));
    }

    @Test
    public void aStayingWriteVacuumsOnlyAtAQuarterPageDead() {
        assertEquals(PagePlacement.APPEND, PagePlacement.forStay(900, 900, 100, MAX));
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forStay(750, 1000, 1, MAX));
        assertEquals(PagePlacement.APPEND, PagePlacement.forStay(751, 1000, 1, MAX));
    }

    @Test
    public void test_a_compact_stay_appends_below_the_floor() {
        assertEquals(PagePlacement.APPEND, PagePlacement.forCompactStay(100, 340, 10, MAX),
                "240 dead bytes are below the quarter-page floor of a 1000-byte page");
    }

    @Test
    public void test_a_compact_stay_vacuums_once_dead_bytes_reach_the_floor_and_the_live_bytes() {
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forCompactStay(100, 350, 10, MAX));
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forCompactStay(300, 600, 10, MAX));
    }

    @Test
    public void test_a_compact_stay_with_fewer_dead_than_live_bytes_falls_back_to_the_stay_rule() {
        assertEquals(PagePlacement.APPEND, PagePlacement.forCompactStay(500, 800, 10, MAX));
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forCompactStay(600, 1000, 10, MAX),
                "past the cap the quarter-page rule still applies");
    }

    @Test
    public void test_a_compact_stay_floor_is_capped_by_the_dead_bytes_constant() {
        final var large = 4L * 1024 * 1024;
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forCompactStay(1000, 1000 + 16 * 1024, 10, large));
        assertEquals(PagePlacement.APPEND, PagePlacement.forCompactStay(1000, 1000 + 16 * 1024 - 1, 10, large));
    }

    @Test
    public void anInsertNeedsLiveRoomAndEitherFileRoomOrAVacuumablePage() {
        assertTrue(PagePlacement.acceptsInsert(500, 900, 100, MAX));
        assertFalse(PagePlacement.acceptsInsert(800, 901, 100, MAX), "no file room and only 101 bytes dead");
        assertTrue(PagePlacement.acceptsInsert(500, 1000, 100, MAX), "a 500-byte dead page is worth vacuuming");
        assertTrue(PagePlacement.acceptsInsert(750, 1000, 100, MAX));
        assertFalse(PagePlacement.acceptsInsert(901, 901, 100, MAX), "no live room");
        assertFalse(PagePlacement.acceptsInsert(751, 1000, 100, MAX));
    }

    @Test
    public void test_accepts_insert_implies_live_bytes_admit() {
        for (var live = 0L; live <= MAX + 100; live += 37) {
            for (var file = 0L; file <= MAX + 100; file += 41) {
                for (var inserted = 1L; inserted <= 400; inserted += 53) {
                    if (PagePlacement.acceptsInsert(live, file, inserted, MAX)) {
                        assertTrue(PagePlacement.liveBytesAdmit(live, inserted, MAX),
                                live + "/" + file + "/" + inserted);
                    }
                }
            }
        }
        assertTrue(PagePlacement.liveBytesAdmit(900, 100, MAX));
        assertFalse(PagePlacement.liveBytesAdmit(901, 100, MAX));
    }

    @Test
    public void test_a_delete_vacuums_at_a_quarter_page_dead() {
        assertTrue(PagePlacement.vacuumsAfterDelete(750, 1000, MAX), "a quarter page dead");
        assertFalse(PagePlacement.vacuumsAfterDelete(751, 1000, MAX), "less than a quarter page dead");
        assertTrue(PagePlacement.vacuumsAfterDelete(900, 1400, MAX), "a page grown past the cap");
    }

    @Test
    public void test_an_emptied_page_is_vacuumed_after_any_write() {
        for (final var emptied : List.of(0L, -5L)) {
            assertTrue(PagePlacement.vacuumsAfterWrite(emptied, 30, MAX), "an emptied page always goes");
            assertTrue(PagePlacement.vacuumsAfterDelete(emptied, 30, MAX), "a stale count still empties the page");
        }
        assertFalse(PagePlacement.vacuumsAfterWrite(0, 0, MAX), "an empty file has nothing to give back");
        assertFalse(PagePlacement.vacuumsAfterDelete(0, 0, MAX));
    }

    @Test
    public void test_a_small_page_vacuums_after_write_once_its_dead_bytes_reach_the_floor_and_its_live_bytes() {
        final var twoMb = 2L * 1024 * 1024;
        final var floor = 16 * 1024;
        assertTrue(PagePlacement.vacuumsAfterWrite(1000, 1000 + floor, twoMb));
        assertTrue(PagePlacement.vacuumsAfterDelete(1000, 1000 + floor, twoMb));
        assertFalse(PagePlacement.vacuumsAfterWrite(1000, 1000 + floor - 1, twoMb), "below the 16 KB floor");
        assertFalse(PagePlacement.vacuumsAfterDelete(1000, 1000 + floor - 1, twoMb));
        assertTrue(PagePlacement.vacuumsAfterWrite(40_000, 80_000, twoMb));
        assertFalse(PagePlacement.vacuumsAfterWrite(40_000, 79_999, twoMb), "fewer dead than live bytes");
    }

    @Test
    public void test_a_large_page_is_left_to_the_placement_rules_after_an_update_and_vacuumed_at_a_quarter_after_a_delete() {
        final var twoMb = 2L * 1024 * 1024;
        final var quarterDead = twoMb - twoMb / 4;
        assertFalse(PagePlacement.vacuumsAfterWrite(quarterDead, twoMb, twoMb),
                "rewriting 1.5 MB to free 512 KB is left to the next update that finds no room");
        assertTrue(PagePlacement.vacuumsAfterDelete(quarterDead, twoMb, twoMb));
        assertFalse(PagePlacement.vacuumsAfterDelete(quarterDead + 1, twoMb, twoMb));
        assertTrue(PagePlacement.vacuumsAfterWrite(700_000, 1_400_000, twoMb), "a 700 KB document's old copy");
    }
}
