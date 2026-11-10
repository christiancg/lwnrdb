package org.techhouse.listen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.techhouse.test.TestUtils;

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

    @Test
    public void test_forgetting_an_idle_key_changes_a_snapshot_taken_before_it() {
        final var snapshot = tracker.snapshot(KEYS);
        assertNotNull(snapshot);
        tracker.begin(KEYS);
        tracker.end(KEYS);

        tracker.forget("db|coll");

        assertFalse(tracker.unchangedSince(snapshot), "a forgotten epoch must not read back as the absent one");
        final var after = tracker.snapshot(KEYS);
        assertNotNull(after);
        assertTrue(tracker.unchangedSince(after));
    }

    @Test
    public void test_forgetting_an_active_key_keeps_its_apply_visible() {
        tracker.begin(KEYS);

        tracker.forget("db|coll");

        assertTrue(tracker.anyActive(KEYS));
        assertNull(tracker.snapshot(KEYS));
        tracker.end(KEYS);
        assertFalse(tracker.anyActive(KEYS));
    }

    @Test
    public void test_forgetting_an_absent_key_leaves_snapshots_valid() {
        final var snapshot = tracker.snapshot(KEYS);
        assertNotNull(snapshot);

        tracker.forget("db|coll");
        tracker.forgetPrefix("other|");

        assertTrue(tracker.unchangedSince(snapshot));
    }

    @Test
    public void test_forgetting_a_database_drops_only_its_idle_keys() {
        tracker.begin(List.of("db|a", "db|b", "dbx|a"));
        tracker.end(List.of("db|a", "dbx|a"));
        final var survivor = tracker.snapshot(List.of("dbx|a"));
        assertNotNull(survivor);

        tracker.forgetPrefix("db|");

        assertTrue(tracker.anyActive(List.of("db|b")));
        assertFalse(tracker.unchangedSince(survivor));
        final var after = tracker.snapshot(List.of("dbx|a", "db|a"));
        assertNotNull(after);
        assertEquals(0L, after.epochs().get("db|a"));
        assertTrue(after.epochs().get("dbx|a") > 0L);
    }

    @Test
    public void aDropDuringAnActiveApplyLeavesNoStateOnceItEnds() throws Exception {
        tracker.begin(KEYS);

        tracker.forget("db|coll");
        tracker.end(KEYS);

        final Map<?, ?> states = TestUtils.getPrivateField(tracker, "states", Map.class);
        assertFalse(states.containsKey("db|coll"), "a key dropped while applying must not outlive its apply");
        assertFalse(tracker.anyActive(KEYS));
    }
}
