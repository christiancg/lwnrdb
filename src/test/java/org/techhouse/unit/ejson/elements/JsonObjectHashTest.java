package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;

public class JsonObjectHashTest {
    private static final int KEY_COUNT = 20000;

    @Test
    public void test_equal_hashcode_keys_round_trip() {
        final var keys = collidingKeys();
        final var object = new JsonObject();
        for (final var key : keys) {
            object.addProperty(key, 1);
        }
        assertEquals(keys.size(), object.size());
        for (final var key : keys) {
            assertEquals(1d, ((JsonNumber) object.get(key)).getValue());
        }
        assertNull(object.get("AaBB_absent"));
    }

    @Test
    public void test_equal_hashcode_keys_do_not_cluster() {
        final var colliding = buildMillis(collidingKeys());
        final var distinct = buildMillis(distinctKeys());
        assertTrue(colliding < Math.max(distinct, 1) * 20 + 500,
                "colliding build " + colliding + "ms vs distinct " + distinct + "ms");
    }

    private static long buildMillis(List<String> keys) {
        final var start = System.nanoTime();
        final var object = new JsonObject();
        for (final var key : keys) {
            object.addProperty(key, 1);
        }
        assertEquals(keys.size(), object.size());
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static List<String> collidingKeys() {
        final var bits = Integer.SIZE - Integer.numberOfLeadingZeros(KEY_COUNT - 1);
        final var keys = new ArrayList<String>(KEY_COUNT);
        for (var i = 0; i < KEY_COUNT; i++) {
            final var key = new StringBuilder();
            for (var b = 0; b < bits; b++) {
                key.append(((i >> b) & 1) == 0 ? "Aa" : "BB");
            }
            keys.add(key.toString());
        }
        return keys;
    }

    private static List<String> distinctKeys() {
        final var keys = new ArrayList<String>(KEY_COUNT);
        for (var i = 0; i < KEY_COUNT; i++) {
            keys.add(String.format("key%08d", i));
        }
        return keys;
    }
}
