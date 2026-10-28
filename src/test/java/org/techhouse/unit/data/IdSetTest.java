package org.techhouse.unit.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IdSet;

public class IdSetTest {
    private static final int COLLIDING_BLOCKS = 7;
    private static final Object FOREIGN_NUMBER = 42;

    private static IdSet of(String... ids) {
        final var set = new IdSet();
        Collections.addAll(set, ids);
        return set;
    }

    private static List<String> collidingIds() {
        var ids = List.of("");
        for (var block = 0; block < COLLIDING_BLOCKS; block++) {
            final var longer = new ArrayList<String>();
            for (final var prefix : ids) {
                longer.add(prefix + "Aa");
                longer.add(prefix + "BB");
            }
            ids = longer;
        }
        return ids;
    }

    private static void assertSameAs(Set<String> oracle, IdSet set) {
        assertEquals(oracle.size(), set.size());
        assertEquals(oracle, set);
        assertEquals(set, oracle);
        assertEquals(oracle.hashCode(), set.hashCode());
        assertEquals(oracle, new HashSet<>(set));
        for (final var id : oracle) {
            assertTrue(set.contains(id), id);
        }
    }

    @Test
    public void test_a_small_set_behaves_like_a_hash_set() {
        final var set = of("b", "a", "c");

        assertSameAs(Set.of("a", "b", "c"), set);
        assertFalse(set.add("a"));
        assertTrue(set.remove("b"));
        assertFalse(set.remove("b"));
        assertFalse(removesANumber(set));
        assertFalse(holdsANumber(set));
        assertFalse(set.contains(null));
        assertSameAs(Set.of("a", "c"), set);
        assertTrue(set.remove("a"));
        assertTrue(set.remove("c"));
        assertEquals(0, set.size());
        assertFalse(set.iterator().hasNext());
    }

    @Test
    public void test_growth_and_shrink_across_the_packed_limit() {
        final var set = new IdSet();
        final var oracle = new HashSet<String>();
        for (var i = 0; i < 40; i++) {
            assertTrue(set.add("id-" + i));
            oracle.add("id-" + i);
            assertSameAs(oracle, set);
        }
        for (var i = 39; i >= 0; i--) {
            assertTrue(set.remove("id-" + i));
            oracle.remove("id-" + i);
            assertSameAs(oracle, set);
        }
        for (var round = 0; round < 6; round++) {
            for (var i = 0; i <= IdSet.PACKED_LIMIT; i++) {
                set.add("edge-" + i);
            }
            assertEquals(IdSet.PACKED_LIMIT + 1, set.size());
            assertTrue(set.remove("edge-" + IdSet.PACKED_LIMIT));
            assertEquals(IdSet.PACKED_LIMIT, set.size());
            set.clear();
        }
    }

    @Test
    public void test_colliding_ids_survive_backward_shift_deletes() {
        final var colliding = collidingIds();
        final var set = new IdSet();
        final var oracle = new HashSet<String>();
        colliding.forEach(id -> {
            set.add(id);
            oracle.add(id);
        });
        assertSameAs(oracle, set);
        final var random = new Random(11L);
        for (var i = 0; i < 2_000; i++) {
            final var id = colliding.get(random.nextInt(colliding.size()));
            if (random.nextInt(3) == 0) {
                assertEquals(oracle.add(id), set.add(id));
            } else {
                assertEquals(oracle.remove(id), set.remove(id));
            }
            assertEquals(oracle.size(), set.size());
            assertEquals(oracle.contains(id), set.contains(id));
        }
        assertSameAs(oracle, set);
    }

    @Test
    public void test_random_operations_match_a_hash_set() {
        final var random = new Random(20261023L);
        final var set = new IdSet();
        final var oracle = new HashSet<String>();
        for (var i = 0; i < 60_000; i++) {
            final var id = "doc-" + random.nextInt(i % 5_000 < 2_500 ? 12 : 3_000);
            switch (random.nextInt(5)) {
                case 0, 1 -> assertEquals(oracle.add(id), set.add(id));
                case 2, 3 -> assertEquals(oracle.remove(id), set.remove(id));
                default -> assertEquals(oracle.contains(id), set.contains(id));
            }
            assertEquals(oracle.size(), set.size());
        }
        assertSameAs(oracle, set);
    }

    @Test
    public void test_a_presized_set_holds_what_it_is_given() {
        final var ids = new ArrayList<String>();
        for (var i = 0; i < 1_000; i++) {
            ids.add("p" + i);
        }
        final var set = new IdSet(ids.size());
        assertTrue(set.addAll(ids));
        assertSameAs(new HashSet<>(ids), set);

        final var small = new IdSet(3);
        small.addAll(List.of("x", "y"));
        assertSameAs(Set.of("x", "y"), small);

        final var sparse = new IdSet(100);
        sparse.add("only");
        assertSameAs(Set.of("only"), sparse);
        assertTrue(sparse.remove("only"));
        assertTrue(sparse.isEmpty());
    }

    private static boolean removesANumber(Collection<?> set) {
        return set.remove(FOREIGN_NUMBER);
    }

    private static boolean holdsANumber(Collection<?> set) {
        return set.contains(FOREIGN_NUMBER);
    }

    @SuppressWarnings("SuspiciousMethodCalls")
    private static boolean removeAllOf(Set<String> target, Collection<?> ids) {
        return target.removeAll(ids);
    }

    @Test
    public void test_bulk_removals_go_through_remove() {
        final var ids = new ArrayList<String>();
        for (var i = 0; i < 50; i++) {
            ids.add("r" + i);
        }
        final var set = new IdSet();
        set.addAll(ids);

        assertTrue(set.removeAll(Set.of("r1", "r2", "r3", "absent")));
        assertFalse(set.removeAll(Set.of("absent")));
        assertTrue(set.retainAll(new HashSet<>(ids.subList(0, 20))));
        assertFalse(set.retainAll(new HashSet<>(ids)));
        assertTrue(set.removeIf(id -> id.endsWith("0")));
        assertFalse(set.removeIf(id -> id.startsWith("z")));

        final var oracle = new HashSet<>(ids.subList(0, 20));
        List.of("r1", "r2", "r3").forEach(oracle::remove);
        oracle.removeIf(id -> id.endsWith("0"));
        assertSameAs(oracle, set);

        assertTrue(removeAllOf(set, set));
        assertTrue(set.isEmpty());
        assertFalse(removeAllOf(set, set));
        final var many = of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        assertTrue(many.removeAll(new HashSet<>(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"))));
        assertTrue(many.isEmpty());
    }

    @Test
    public void test_the_iterator_refuses_to_remove_and_fails_fast() {
        final var small = of("a", "b");
        final var smallIterator = small.iterator();
        smallIterator.next();
        assertThrows(UnsupportedOperationException.class, smallIterator::remove);

        final var large = new IdSet();
        for (var i = 0; i < 20; i++) {
            large.add("l" + i);
        }
        final var iterator = large.iterator();
        iterator.next();
        large.add("late");
        assertThrows(ConcurrentModificationException.class, iterator::next);

        final var exhausted = of("only").iterator();
        exhausted.next();
        assertFalse(exhausted.hasNext());
        assertThrows(NoSuchElementException.class, exhausted::next);
    }

    @Test
    public void test_null_is_refused_and_clear_empties() {
        final var set = of("a");
        assertThrows(NullPointerException.class, () -> set.add(null));
        set.clear();
        assertEquals(new HashSet<String>(), set);
        assertTrue(set.add("b"));
        assertEquals(Set.of("b"), set);
        assertNotEquals(of("a"), of("b"));
        assertEquals("[a]", of("a").toString());
    }

    @Test
    public void test_slots_follow_the_packed_array_then_the_table() {
        assertEquals(0, IdSet.slotsFor(0));
        assertEquals(1, IdSet.slotsFor(1));
        assertEquals(IdSet.PACKED_LIMIT, IdSet.slotsFor(IdSet.PACKED_LIMIT));
        assertEquals(16, IdSet.slotsFor(IdSet.PACKED_LIMIT + 1));
        assertEquals(16, IdSet.slotsFor(12));
        assertEquals(32, IdSet.slotsFor(13));
        assertEquals(1_024, IdSet.slotsFor(768));
        assertEquals(2_048, IdSet.slotsFor(769));
    }

    @Test
    public void test_a_parsed_index_line_holds_an_id_set() {
        final var separator = Globals.ID_SEPARATOR;
        final var one = FieldIndexEntry.fromIndexFileEntry("db", "coll", "5" + separator + "a", Double.class);
        final var many = FieldIndexEntry.fromIndexFileEntry("db", "coll",
                "red" + separator + String.join(separator, List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")),
                String.class);

        assertInstanceOf(IdSet.class, one.getIds());
        assertInstanceOf(IdSet.class, many.getIds());
        assertEquals(Set.of("a"), one.getIds());
        assertEquals(10, many.getIds().size());
    }

    @Test
    public void test_stored_returns_the_instance_the_set_holds() {
        for (final var count : List.of(3, 40)) {
            final var set = new IdSet();
            final var held = new HashMap<String, String>();
            for (var i = 0; i < count; i++) {
                final var id = "id" + i;
                set.add(id);
                held.put(id, id);
            }
            for (var i = 0; i < count; i++) {
                final var probe = "id" + i;
                assertSame(held.get(probe), set.stored(probe));
            }
            assertNull(set.stored("absent"));
        }
    }
}
