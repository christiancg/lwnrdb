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
    public void anInsertNeedsLiveRoomAndEitherFileRoomOrAVacuumablePage() {
        assertTrue(PagePlacement.acceptsInsert(500, 900, 100, MAX));
        assertFalse(PagePlacement.acceptsInsert(800, 901, 100, MAX), "no file room and only 101 bytes dead");
        assertTrue(PagePlacement.acceptsInsert(500, 1000, 100, MAX), "a 500-byte dead page is worth vacuuming");
        assertTrue(PagePlacement.acceptsInsert(750, 1000, 100, MAX));
        assertFalse(PagePlacement.acceptsInsert(901, 901, 100, MAX), "no live room");
        assertFalse(PagePlacement.acceptsInsert(751, 1000, 100, MAX));
    }
}
