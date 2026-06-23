package org.techhouse.unit.bckg_ops;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.bckg_ops.PendingIndexWrites;

public class PendingIndexWritesTest {
    private PendingIndexWrites pending;

    @BeforeEach
    public void setUp() {
        pending = new PendingIndexWrites();
    }

    // A marked id appears in the collection's pending snapshot until it is cleared
    @Test
    public void test_mark_then_clear_lifecycle() {
        pending.mark("db", "coll", "1");
        assertEquals(Set.of("1"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", "1");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
    }

    // Counts: an id marked twice needs two clears before it disappears
    @Test
    public void test_double_mark_needs_double_clear() {
        pending.mark("db", "coll", "1");
        pending.mark("db", "coll", "1");
        pending.clear("db", "coll", "1");
        assertEquals(Set.of("1"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", "1");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
    }

    // Clearing an id that was never marked is a no-op (does not create a negative entry)
    @Test
    public void test_clear_unmarked_is_no_op() {
        pending.clear("db", "coll", "ghost");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
        // A subsequent mark/clear still behaves correctly
        pending.mark("db", "coll", "ghost");
        assertEquals(Set.of("ghost"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", "ghost");
        assertTrue(pending.idsFor("db", "coll").isEmpty());
    }

    // idsFor returns a detached snapshot: mutating it does not affect the tracker
    @Test
    public void test_ids_for_returns_detached_snapshot() {
        pending.mark("db", "coll", "1");
        final var snapshot = pending.idsFor("db", "coll");
        snapshot.add("2");
        assertEquals(Set.of("1"), pending.idsFor("db", "coll"));
    }

    // Bulk mark/clear of an iterable of ids
    @Test
    public void test_bulk_mark_and_clear() {
        pending.mark("db", "coll", List.of("1", "2", "3"));
        assertEquals(Set.of("1", "2", "3"), pending.idsFor("db", "coll"));
        pending.clear("db", "coll", List.of("1", "2"));
        assertEquals(Set.of("3"), pending.idsFor("db", "coll"));
    }

    // Pending ids are tracked per collection
    @Test
    public void test_pending_is_per_collection() {
        pending.mark("db", "collA", "1");
        assertTrue(pending.idsFor("db", "collB").isEmpty());
        assertEquals(Set.of("1"), pending.idsFor("db", "collA"));
    }

    // idsFor on a never-touched collection returns an empty set
    @Test
    public void test_ids_for_unknown_collection_is_empty() {
        assertTrue(pending.idsFor("db", "unknown").isEmpty());
    }
}
