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

    private static DigestEntry entry(long length) {
        return new DigestEntry("x", 7, false, length);
    }

    @Test
    public void test_equal_versions_with_different_content_do_not_match() {
        final var mine = List.of(entry(120));
        final var theirs = List.of(entry(143));

        assertNotEquals(summaryOf(mine), summaryOf(theirs),
                "two nodes holding different documents at the same version used to produce identical summaries,"
                        + " so the peer answered summaryMatch, no digest was sent, and the equal-version"
                        + " tie-break never ran");
    }

    @Test
    public void test_equal_versions_with_the_same_content_still_match() {
        final var mine = List.of(entry(120));
        final var theirs = List.of(entry(120));

        assertEquals(summaryOf(mine), summaryOf(theirs),
                "converged replicas must still take the cheap path and skip the digest");
    }

    @Test
    public void test_identical_entries_fill_identical_bucket_vectors() {
        final var entries = new java.util.ArrayList<DigestEntry>();
        for (var i = 0; i < 500; i++) {
            entries.add(new DigestEntry("d" + i, 7 + i, i % 7 == 0, i % 7 == 0 ? 0 : 40 + i));
        }
        final var reversed = new java.util.ArrayList<>(entries);
        java.util.Collections.reverse(reversed);

        final var mine = LocalDigest.ofEntries(entries, 64);
        final var theirs = LocalDigest.ofEntries(reversed, 64);

        assertEquals(mine.bucketSummaries(), theirs.bucketSummaries());
        assertTrue(mine.differingBuckets(theirs).isEmpty());
        assertTrue(mine.differingBuckets(theirs.bucketSummaries()).isEmpty());
    }

    @Test
    public void test_one_changed_entry_differs_in_exactly_its_bucket() {
        final var entries = new java.util.ArrayList<DigestEntry>();
        for (var i = 0; i < 500; i++) {
            entries.add(new DigestEntry("d" + i, 7, false, 40));
        }
        final var changed = new java.util.ArrayList<>(entries);
        changed.set(123, new DigestEntry("d123", 8, false, 40));

        final var differing = LocalDigest.ofEntries(entries, 64).differingBuckets(LocalDigest.ofEntries(changed, 64));

        assertEquals(java.util.Set.of(DigestHash.bucket(DigestHash.idHash("d123"), 64)), differing);
    }

    @Test
    public void test_omitting_the_length_changes_the_summary() {
        assertNotEquals(summaryOf(List.of(new DigestEntry("a", 7, false, 120))),
                summaryOf(List.of(new DigestEntry("a", 7, false))),
                "a live entry built without its length can never match the peer's, which is why reconcile"
                        + " must not use the length-less constructor");
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
