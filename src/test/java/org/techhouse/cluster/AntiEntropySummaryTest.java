package org.techhouse.cluster;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.msg.DigestEntry;

public class AntiEntropySummaryTest {
    private static String summaryOf(List<DigestEntry> entries) {
        return AntiEntropyService.summaryOf(entries);
    }

    private static DigestEntry entry(String id, long version, boolean deleted) {
        return new DigestEntry(id, version, deleted);
    }

    @Test
    public void test_identical_contents_produce_the_same_summary() {
        final var a = List.of(entry("a", 1, false), entry("b", 2, false));
        final var b = List.of(entry("a", 1, false), entry("b", 2, false));

        assertEquals(summaryOf(a), summaryOf(b));
    }

    @Test
    public void test_order_does_not_change_the_summary() {
        final var ascending = List.of(entry("a", 1, false), entry("b", 2, false), entry("c", 3, true));
        final var shuffled = List.of(entry("c", 3, true), entry("a", 1, false), entry("b", 2, false));

        assertEquals(summaryOf(ascending), summaryOf(shuffled));
    }

    @Test
    public void test_a_different_version_changes_the_summary() {
        assertNotEquals(summaryOf(List.of(entry("a", 1, false))), summaryOf(List.of(entry("a", 2, false))));
    }

    @Test
    public void test_a_tombstone_differs_from_a_live_entry_of_the_same_version() {
        assertNotEquals(summaryOf(List.of(entry("a", 1, false))), summaryOf(List.of(entry("a", 1, true))));
    }

    @Test
    public void test_a_missing_entry_changes_the_summary() {
        assertNotEquals(summaryOf(List.of(entry("a", 1, false), entry("b", 1, false))),
                summaryOf(List.of(entry("a", 1, false))));
    }

    @Test
    public void test_an_extra_entry_changes_the_summary() {
        assertNotEquals(summaryOf(List.of(entry("a", 1, false))),
                summaryOf(List.of(entry("a", 1, false), entry("z", 9, true))));
    }

    @Test
    public void test_an_empty_collection_has_a_stable_summary() {
        assertEquals(summaryOf(List.of()), summaryOf(List.of()));
        assertNotEquals(summaryOf(List.of()), summaryOf(List.of(entry("a", 1, false))));
    }

    @Test
    public void test_the_summary_carries_the_entry_count() {
        assertTrue(summaryOf(List.of(entry("a", 1, false), entry("b", 1, false))).startsWith("2:"));
    }

    @Test
    public void test_ids_that_concatenate_alike_are_still_distinguished() {
        assertNotEquals(summaryOf(List.of(entry("a|1", 2, false))), summaryOf(List.of(entry("a", 1, false))));
    }
}
