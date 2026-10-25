package org.techhouse.unit.fs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    public void anUpdateVacuumsWhenAQuarterPageWouldBeFreeAfterward() {
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forUpdate(750, 950, 100, MAX));
        assertEquals(PagePlacement.VACUUM_THEN_APPEND, PagePlacement.forUpdate(100, 1000, 1, MAX));
    }

    @Test
    public void anUpdateRelocatesWhenTheLiveDataLeavesLessThanAQuarterFree() {
        assertEquals(PagePlacement.RELOCATE, PagePlacement.forUpdate(751, 950, 100, MAX));
        assertEquals(PagePlacement.RELOCATE, PagePlacement.forUpdate(1100, 1000, 200, MAX));
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
    public void test_vacuums_after_delete() {
        assertTrue(PagePlacement.vacuumsAfterDelete(750, 1000, MAX), "a quarter page dead");
        assertFalse(PagePlacement.vacuumsAfterDelete(751, 1000, MAX), "less than a quarter page dead");
        assertTrue(PagePlacement.vacuumsAfterDelete(0, 30, MAX), "an emptied page always goes");
        assertTrue(PagePlacement.vacuumsAfterDelete(-5, 30, MAX), "a stale count still empties the page");
        assertFalse(PagePlacement.vacuumsAfterDelete(0, 0, MAX), "an empty file has nothing to give back");
        assertTrue(PagePlacement.vacuumsAfterDelete(900, 1400, MAX), "a page grown past the cap");
    }
}
