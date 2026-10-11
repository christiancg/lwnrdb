package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.utils.JsonUtils;

public class JsonUtilsNestingTest {
    private static final int LIMIT = 4;

    private static JsonObject chainOf(int depth) {
        JsonBaseElement inner = new JsonObject();
        for (var level = 1; level < depth; level++) {
            final var wrapper = new JsonObject();
            wrapper.add("a", inner);
            inner = wrapper;
        }
        return inner.asJsonObject();
    }

    @Test
    public void depth_below_and_at_the_limit_does_not_exceed() {
        assertFalse(JsonUtils.nestingExceeds(chainOf(LIMIT - 1), LIMIT));
        assertFalse(JsonUtils.nestingExceeds(chainOf(LIMIT), LIMIT));
    }

    @Test
    public void depth_past_the_limit_exceeds() {
        assertTrue(JsonUtils.nestingExceeds(chainOf(LIMIT + 1), LIMIT));
    }

    @Test
    public void arrays_count_as_levels() {
        final var array = new JsonArray();
        array.add(new JsonArray());
        final var root = chainOf(LIMIT - 1);
        var innermost = root;
        while (innermost.has("a")) {
            innermost = innermost.get("a").asJsonObject();
        }
        innermost.add("list", array);

        assertTrue(JsonUtils.nestingExceeds(root, LIMIT));
        assertFalse(JsonUtils.nestingExceeds(root, LIMIT + 1));
    }

    @Test
    public void scalars_do_not_add_a_level() {
        final var root = chainOf(LIMIT);
        root.add("n", new JsonNumber(1));

        assertFalse(JsonUtils.nestingExceeds(root, LIMIT));
    }

    @Test
    public void a_very_deep_chain_is_walked_without_overflowing() {
        assertTrue(JsonUtils.nestingExceeds(chainOf(100_000), LIMIT));
        assertFalse(JsonUtils.nestingExceeds(chainOf(100_000), 100_000));
    }
}
