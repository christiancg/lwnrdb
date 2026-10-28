package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.KeyShape;
import org.techhouse.ejson.internal.ShapeTable;

public class ShapeTableTest {
    private static final int RACERS = 8;
    private static final String[] KEYS = {"_id", "name", "price"};

    @Test
    public void test_the_same_keys_return_one_shape() {
        final var table = new ShapeTable(64, 8, 3);

        final var first = Objects.requireNonNull(table.shapeOf(KEYS, 0, KEYS.length));
        final var second = Objects.requireNonNull(table.shapeOf(new String[]{"x", "_id", "name", "price"}, 1, 4));

        assertNotNull(first);
        assertSame(first, second);
        assertEquals(3, first.size());
    }

    @Test
    public void test_a_different_order_is_a_different_shape() {
        final var table = new ShapeTable(64, 8, 3);

        assertNotSame(table.shapeOf(KEYS, 0, 3), table.shapeOf(new String[]{"name", "_id", "price"}, 0, 3));
    }

    @Test
    public void test_duplicate_keys_get_no_shape() {
        assertNull(new ShapeTable(64, 8, 3).shapeOf(new String[]{"a", "a"}, 0, 2));
    }

    @Test
    public void test_a_shape_pins_after_enough_uses() {
        final var table = new ShapeTable(64, 8, 3);
        final var shape = Objects.requireNonNull(table.shapeOf(KEYS, 0, 3));
        assertFalse(shape.isPinned());

        table.shapeOf(KEYS, 0, 3);
        table.shapeOf(KEYS, 0, 3);

        assertTrue(shape.isPinned());
        assertEquals(1, table.pinnedShapes());
    }

    @Test
    public void test_pinning_stops_at_the_bound() {
        final var table = new ShapeTable(64, 1, 2);
        final var first = Objects.requireNonNull(table.shapeOf(new String[]{"p1"}, 0, 1));
        table.shapeOf(new String[]{"p1"}, 0, 1);
        final var second = Objects.requireNonNull(table.shapeOf(new String[]{"p2"}, 0, 1));
        table.shapeOf(new String[]{"p2"}, 0, 1);

        assertTrue(first.isPinned());
        assertFalse(second.isPinned());
        assertEquals(1, table.pinnedShapes());
    }

    @Test
    public void test_a_full_window_evicts_an_unused_unpinned_shape_only() {
        final var table = new ShapeTable(2, 1, 2);
        final var pinned = Objects.requireNonNull(table.shapeOf(new String[]{"kept"}, 0, 1));
        table.shapeOf(new String[]{"kept"}, 0, 1);
        assertTrue(pinned.isPinned());

        for (var i = 0; i < 20; i++) {
            assertNotNull(table.shapeOf(new String[]{"churn" + i}, 0, 1));
        }

        assertSame(pinned, table.shapeOf(new String[]{"kept"}, 0, 1));
    }

    @Test
    public void test_the_slot_count_must_be_a_power_of_two() {
        assertThrows(IllegalArgumentException.class, () -> new ShapeTable(3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShapeTable(1, 1, 1));
    }

    @Test
    public void test_a_pinned_shape_pins_once() {
        final var shape = Objects.requireNonNull(new ShapeTable(64, 8, 100).shapeOf(KEYS, 0, 3));

        assertTrue(shape.pin());
        assertFalse(shape.pin());
    }

    @Test
    public void test_concurrent_lookups_of_one_key_sequence_share_one_shape() throws Exception {
        final var table = new ShapeTable(1024, 64, 4);
        final Set<KeyShape> seen = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        final var start = new CountDownLatch(1);
        final var done = new CountDownLatch(RACERS);
        final var failure = new AtomicReference<Throwable>();
        table.shapeOf(KEYS, 0, 3);
        for (var racer = 0; racer < RACERS; racer++) {
            Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                    for (var round = 0; round < 10_000; round++) {
                        seen.add(table.shapeOf(new String[]{"_id", "name", "price"}, 0, 3));
                    }
                } catch (InterruptedException | RuntimeException e) {
                    failure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();

        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(1, seen.size());
        assertTrue(seen.iterator().next().isPinned());
        assertEquals(1, table.pinnedShapes());
    }

    @Test
    public void test_a_shape_past_the_key_bound_is_not_shared() {
        final var table = new ShapeTable(64, 8, 3);
        final var keys = new String[Globals.SHAPE_TABLE_MAX_KEYS + 1];
        for (var i = 0; i < keys.length; i++) {
            keys[i] = "bounded" + i;
        }

        assertNull(table.shapeOf(keys, 0, keys.length));
        assertNotNull(table.shapeOf(keys, 0, Globals.SHAPE_TABLE_MAX_KEYS));
    }

    @Test
    public void test_a_key_past_the_key_table_length_is_not_shared() {
        final var table = new ShapeTable(64, 8, 3);
        final var longKey = "k".repeat(Globals.KEY_TABLE_MAX_KEY_LENGTH + 1);

        assertNull(table.shapeOf(new String[]{"_id", longKey}, 0, 2));
        assertNotNull(table.shapeOf(new String[]{"_id", longKey.substring(1)}, 0, 2));
    }
}
