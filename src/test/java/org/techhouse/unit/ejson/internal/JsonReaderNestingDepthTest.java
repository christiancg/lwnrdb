package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.MalformedJsonException;

public class JsonReaderNestingDepthTest {
    private static final int LIMIT = 8;
    private final EJson eJson = new EJson();

    private static String nestedObjects(int depth) {
        return "{\"a\":".repeat(depth - 1) + "{}" + "}".repeat(depth - 1);
    }

    private static String objectsAroundArrays(int depth) {
        final var prefix = new StringBuilder("{\"a\":");
        final var suffix = new StringBuilder("}");
        for (var level = 2; level < depth; level++) {
            prefix.append(level % 2 == 0 ? "[" : "{\"a\":");
            suffix.insert(0, level % 2 == 0 ? "]" : "}");
        }
        return prefix + "[]" + suffix;
    }

    @Test
    public void depth_at_the_limit_parses() {
        assertDoesNotThrow(() -> eJson.fromJson(nestedObjects(LIMIT), JsonObject.class, LIMIT));
        assertDoesNotThrow(() -> eJson.fromJson(objectsAroundArrays(LIMIT), JsonObject.class, LIMIT));
    }

    @Test
    public void depth_past_the_limit_throws_malformed() {
        assertThrows(MalformedJsonException.class,
                () -> eJson.fromJson(nestedObjects(LIMIT + 1), JsonObject.class, LIMIT));
        assertThrows(MalformedJsonException.class,
                () -> eJson.fromJson(objectsAroundArrays(LIMIT + 1), JsonObject.class, LIMIT));
    }

    @Test
    public void unlimited_overload_parses_beyond_the_limit() {
        assertDoesNotThrow(() -> eJson.fromJson(nestedObjects(LIMIT * 4), JsonObject.class));
    }
}
