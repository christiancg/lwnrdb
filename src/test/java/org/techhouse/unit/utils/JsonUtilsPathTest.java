package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.utils.JsonUtils;

public class JsonUtilsPathTest {
    private static JsonObject nested() {
        final var leaf = new JsonObject();
        leaf.addProperty("c", "deep");
        leaf.add("nullLeaf", JsonNull.INSTANCE);
        final var middle = new JsonObject();
        middle.add("b", leaf);
        middle.addProperty("scalar", 7);
        final var root = new JsonObject();
        root.add("a", middle);
        root.addProperty("top", "value");
        root.add("nullTop", JsonNull.INSTANCE);
        return root;
    }

    @Test
    public void test_single_segment_resolves() {
        assertEquals("value", JsonUtils.getFromPath(nested(), "top").asJsonString().getValue());
        assertTrue(JsonUtils.hasInPath(nested(), "top"));
    }

    @Test
    public void test_nested_segments_resolve() {
        assertEquals("deep", JsonUtils.getFromPath(nested(), "a.b.c").asJsonString().getValue());
        assertTrue(JsonUtils.hasInPath(nested(), "a.b.c"));
    }

    @Test
    public void test_absent_field_is_distinct_from_stored_null() {
        final var obj = nested();

        assertFalse(JsonUtils.hasInPath(obj, "missing"));
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(obj, "missing"));
        assertNull(JsonUtils.resolvePath(obj, "missing"));

        assertTrue(JsonUtils.hasInPath(obj, "nullTop"));
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(obj, "nullTop"));
        assertEquals(JsonNull.INSTANCE, JsonUtils.resolvePath(obj, "nullTop"));
    }

    @Test
    public void test_stored_null_at_a_nested_path_is_present() {
        final var obj = nested();
        assertTrue(JsonUtils.hasInPath(obj, "a.b.nullLeaf"));
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(obj, "a.b.nullLeaf"));
        assertEquals(JsonNull.INSTANCE, JsonUtils.resolvePath(obj, "a.b.nullLeaf"));
    }

    @Test
    public void test_absent_nested_segment_reports_missing() {
        assertFalse(JsonUtils.hasInPath(nested(), "a.b.nope"));
        assertFalse(JsonUtils.hasInPath(nested(), "a.nope.c"));
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(nested(), "a.b.nope"));
    }

    @Test
    public void test_non_object_intermediate_keeps_the_cursor() {
        final var obj = nested();
        assertTrue(JsonUtils.hasInPath(obj, "a.scalar.b"));
        assertSame(JsonUtils.getFromPath(obj, "a.b"), JsonUtils.getFromPath(obj, "a.scalar.b"));
        assertTrue(JsonUtils.hasInPath(obj, "a.scalar.scalar"));
        assertEquals(7, JsonUtils.getFromPath(obj, "a.scalar.scalar").asJsonNumber().getValue());
        assertFalse(JsonUtils.hasInPath(obj, "a.scalar.nope"));
    }

    @Test
    public void test_trailing_dots_are_ignored_like_split() {
        final var obj = nested();
        assertEquals("value", JsonUtils.getFromPath(obj, "top.").asJsonString().getValue());
        assertEquals("value", JsonUtils.getFromPath(obj, "top...").asJsonString().getValue());
        assertTrue(JsonUtils.hasInPath(obj, "top."));
    }

    @Test
    public void test_path_of_only_dots_behaves_like_an_empty_segment_list() {
        final var obj = nested();
        assertTrue(JsonUtils.hasInPath(obj, "."));
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(obj, "."));
    }

    @Test
    public void test_empty_path_is_a_missing_field() {
        final var obj = nested();
        assertFalse(JsonUtils.hasInPath(obj, ""));
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(obj, ""));
    }

    @Test
    public void test_interior_empty_segment_is_a_missing_field() {
        assertFalse(JsonUtils.hasInPath(nested(), "a..b"));
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(nested(), "a..b"));
    }
}
