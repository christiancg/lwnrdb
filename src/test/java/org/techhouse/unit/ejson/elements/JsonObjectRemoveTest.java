package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.KeyShape;

public class JsonObjectRemoveTest {
    private static final int KEYS = 40;
    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();

    private static String key(int i) {
        return "member" + i;
    }

    private static JsonObject wide(Map<String, JsonBaseElement> mirror) {
        final var object = new JsonObject();
        for (var i = 0; i < KEYS; i++) {
            final var value = new JsonNumber(i);
            object.add(key(i), value);
            mirror.put(key(i), value);
        }
        return object;
    }

    private static JsonObject shared(Map<String, JsonBaseElement> mirror) {
        final var keys = new String[KEYS];
        final var values = new JsonBaseElement[KEYS];
        for (var i = 0; i < KEYS; i++) {
            keys[i] = key(i);
            values[i] = new JsonNumber(i);
            mirror.put(keys[i], values[i]);
        }
        final KeyShape shape = JsonObject.shapeOf(keys, 0, KEYS);
        assertNotNull(shape);
        return JsonObject.withShape(shape, values);
    }

    private static JsonObject rebuilt(Map<String, JsonBaseElement> mirror) {
        final var object = new JsonObject();
        mirror.forEach(object::add);
        return object;
    }

    private static void assertMatches(Map<String, JsonBaseElement> mirror, JsonObject object) {
        assertEquals(mirror.size(), object.size());
        var position = 0;
        for (final var entry : mirror.entrySet()) {
            assertEquals(entry.getKey(), object.keyAt(position));
            assertEquals(position, object.indexOf(entry.getKey()));
            assertEquals(entry.getValue(), object.get(entry.getKey()));
            position++;
        }
        for (var i = 0; i < KEYS; i++) {
            assertEquals(mirror.containsKey(key(i)), object.has(key(i)), key(i));
        }
        final var expected = rebuilt(mirror);
        assertEquals(expected, object);
        assertEquals(object, expected);
        assertEquals(expected.hashCode(), object.hashCode());
    }

    private static void removeRandomly(JsonObject object, Map<String, JsonBaseElement> mirror, long seed) {
        final var random = new Random(seed);
        final var remaining = new ArrayList<>(mirror.keySet());
        for (var round = 0; round < 20; round++) {
            final var victim = remaining.remove(random.nextInt(remaining.size()));
            object.remove(victim);
            mirror.remove(victim);
            assertMatches(mirror, object);
        }
    }

    @Test
    public void test_random_removals_keep_every_lookup_right() {
        for (var seed = 0L; seed < 25; seed++) {
            final var mirror = new LinkedHashMap<String, JsonBaseElement>();
            final var object = wide(mirror);
            removeRandomly(object, mirror, seed);
        }
    }

    @Test
    public void test_random_removals_on_a_shared_shape_leave_the_shape_alone() {
        final var mirror = new LinkedHashMap<String, JsonBaseElement>();
        final var object = shared(mirror);
        final var siblingValues = new JsonBaseElement[KEYS];
        for (var i = 0; i < KEYS; i++) {
            siblingValues[i] = new JsonNumber(i);
        }
        final var sibling = JsonObject.withShape(object.shape(), siblingValues);

        removeRandomly(object, mirror, 7);

        assertNull(object.shape());
        assertNotNull(sibling.shape());
        for (var i = 0; i < KEYS; i++) {
            assertEquals(i, sibling.indexOf(key(i)));
        }
    }

    @Test
    public void test_a_put_after_removals_is_found() {
        final var mirror = new LinkedHashMap<String, JsonBaseElement>();
        final var object = wide(mirror);
        removeRandomly(object, mirror, 3);
        for (var i = 0; i < 10; i++) {
            final var value = new JsonNumber(100 + i);
            object.add("late" + i, value);
            mirror.put("late" + i, value);
            assertMatches(mirror, object);
        }
        object.add(mirror.keySet().iterator().next(), new JsonNumber(-1));
        assertEquals(new JsonNumber(-1), object.get(mirror.keySet().iterator().next()));
    }

    @Test
    public void test_the_index_is_dropped_once_sixteen_keys_remain() {
        final var object = wide(new LinkedHashMap<>());
        for (var i = 0; i < KEYS - 17; i++) {
            object.remove(key(i));
        }
        assertTrue(object.indexSlots() > 0);
        object.remove(key(KEYS - 17));
        assertEquals(16, object.size());
        assertEquals(0, object.indexSlots());
        assertEquals(KEYS - 1, object.get(key(KEYS - 1)).asJsonNumber().getValue().intValue());
        assertFalse(object.has(key(0)));
    }

    @Test
    public void test_a_removal_from_an_indexed_object_allocates_almost_nothing() {
        final var keys = new ArrayList<String>();
        for (var i = 0; i < KEYS; i++) {
            keys.add(key(i));
        }
        var cheapest = Long.MAX_VALUE;
        for (var round = 0; round < 5; round++) {
            final var object = wide(new LinkedHashMap<>());
            final List<String> victims = keys.subList(0, 8);
            final var before = THREADS.getCurrentThreadAllocatedBytes();
            for (final var victim : victims) {
                object.remove(victim);
            }
            cheapest = Math.min(cheapest, (THREADS.getCurrentThreadAllocatedBytes() - before) / victims.size());
        }
        assertTrue(cheapest < 64, "one removal allocated " + cheapest + " bytes");
    }
}
