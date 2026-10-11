package org.techhouse.listen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class ApplyTrackerTest {
    private static final List<String> KEYS = List.of("db|coll");
    private final ApplyTracker tracker = new ApplyTracker();

    @Test
    public void test_a_key_never_applied_snapshots_and_stays_unchanged() {
        final var snapshot = tracker.snapshot(KEYS);

        assertNotNull(snapshot);
        assertTrue(tracker.unchangedSince(snapshot));
        assertFalse(tracker.anyActive(KEYS));
    }

    @Test
    public void test_an_active_apply_refuses_a_snapshot() {
        tracker.begin(KEYS);

        assertNull(tracker.snapshot(KEYS));
        assertTrue(tracker.anyActive(KEYS));
    }

    @Test
    public void test_an_apply_that_begins_after_the_snapshot_changes_it() {
        final var snapshot = tracker.snapshot(KEYS);
        assertNotNull(snapshot);

        tracker.begin(KEYS);

        assertFalse(tracker.unchangedSince(snapshot));
    }

    @Test
    public void test_an_apply_that_begins_and_ends_during_the_read_changes_it() {
        final var snapshot = tracker.snapshot(KEYS);
        assertNotNull(snapshot);

        tracker.begin(KEYS);
        tracker.end(KEYS);

        assertFalse(tracker.unchangedSince(snapshot));
        assertFalse(tracker.anyActive(KEYS));
    }

    @Test
    public void test_a_snapshot_after_the_apply_ended_is_stable() {
        tracker.begin(KEYS);
        tracker.end(KEYS);

        final var snapshot = tracker.snapshot(KEYS);

        assertNotNull(snapshot);
        assertTrue(tracker.unchangedSince(snapshot));
    }

    @Test
    public void test_an_unrelated_key_does_not_block_a_snapshot() {
        tracker.begin(List.of("db|other"));

        assertNotNull(tracker.snapshot(KEYS));
    }

    @Test
    public void test_ending_an_unknown_key_is_harmless() {
        tracker.end(KEYS);
        tracker.clear();

        assertNotNull(tracker.snapshot(KEYS));
    }
}
