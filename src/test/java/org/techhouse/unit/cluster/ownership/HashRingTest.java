package org.techhouse.unit.cluster.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.cluster.ownership.HashRing;

public class HashRingTest {

    @Test
    public void test_empty_ring_owns_nothing() {
        final var ring = new HashRing(List.of(), 128);
        assertTrue(ring.isEmpty());
        assertNull(ring.owner("db|coll"));
    }

    @Test
    public void test_single_node_owns_everything() {
        final var ring = new HashRing(List.of("only"), 64);
        for (var i = 0; i < 100; i++) {
            assertEquals("only", ring.owner("db|coll-" + i));
        }
    }

    @Test
    public void test_single_virtual_node_wraps_around_the_ring() {
        final var ring = new HashRing(List.of("only"), 1);
        for (var i = 0; i < 200; i++) {
            assertEquals("only", ring.owner("db|coll-" + i));
        }
    }

    @Test
    public void test_ownership_is_deterministic() {
        final var a = new HashRing(List.of("n1", "n2", "n3"), 128);
        final var b = new HashRing(List.of("n1", "n2", "n3"), 128);
        for (var i = 0; i < 200; i++) {
            final var key = "db|coll-" + i;
            assertEquals(a.owner(key), b.owner(key));
        }
    }

    @Test
    public void test_distribution_uses_all_nodes() {
        final var ring = new HashRing(List.of("n1", "n2", "n3"), 128);
        final var owners = new HashSet<String>();
        for (var i = 0; i < 500; i++) {
            owners.add(ring.owner("db|coll-" + i));
        }
        assertEquals(3, owners.size());
    }

    @Test
    public void test_adding_a_node_reshuffles_a_minority_of_keys() {
        final var before = new HashRing(List.of("n1", "n2", "n3"), 128);
        final var after = new HashRing(List.of("n1", "n2", "n3", "n4"), 128);
        final var owners = new HashMap<String, String>();
        for (var i = 0; i < 1000; i++) {
            owners.put("db|coll-" + i, before.owner("db|coll-" + i));
        }
        var moved = 0;
        for (var entry : owners.entrySet()) {
            if (!entry.getValue().equals(after.owner(entry.getKey()))) {
                moved++;
            }
        }
        assertTrue(moved < 500, "Expected less than half of keys to move, but " + moved + " moved");
    }
}
