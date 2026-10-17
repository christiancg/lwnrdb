package org.techhouse.unit.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.cache.PartitionedMissCache;

public class PartitionedMissCacheTest {

    @Test
    public void eachDatabaseKeepsAtMostItsOwnSlice() {
        final var misses = new PartitionedMissCache(4096);
        for (var i = 0; i < 1000; i++) {
            misses.put("a", "k" + i);
        }
        assertEquals(PartitionedMissCache.MAX_ENTRIES_PER_DATABASE, misses.size());
        assertTrue(misses.contains("a", "k999"));
        assertFalse(misses.contains("a", "k0"));
    }

    @Test
    public void totalStaysWithinTheEntryBudgetByDroppingTheLeastRecentDatabase() {
        final var misses = new PartitionedMissCache(300);
        for (var i = 0; i < 200; i++) {
            misses.put("old", "k" + i);
        }
        for (var i = 0; i < 200; i++) {
            misses.put("new", "k" + i);
        }
        assertTrue(misses.size() <= 300);
        assertFalse(misses.contains("old", "k0"));
        assertTrue(misses.contains("new", "k199"));
    }

    @Test
    public void aSmallBudgetCapsTheSliceToo() {
        final var misses = new PartitionedMissCache(16);
        for (var i = 0; i < 100; i++) {
            misses.put("a", "k" + i);
        }
        assertEquals(16, misses.size());
    }

    @Test
    public void zeroBudgetRemembersNothing() {
        final var misses = new PartitionedMissCache(0);
        misses.put("a", "k");
        assertFalse(misses.contains("a", "k"));
        assertEquals(0, misses.size());
    }

    @Test
    public void removalsAreScopedToTheirDatabase() {
        final var misses = new PartitionedMissCache(4096);
        misses.put("a", "p|a|x");
        misses.put("a", "s|a|y");
        misses.put("b", "p|b|x");
        misses.remove("a", "p|a|x");
        misses.remove("missing", "p|missing|x");
        assertFalse(misses.contains("a", "p|a|x"));
        assertTrue(misses.contains("a", "s|a|y"));
        misses.removeIf("a", key -> key.startsWith("s|"));
        misses.removeIf("missing", _ -> true);
        assertFalse(misses.contains("a", "s|a|y"));
        assertTrue(misses.contains("b", "p|b|x"));
        assertEquals(1, misses.size());
        misses.clear();
        assertEquals(0, misses.size());
    }
}
