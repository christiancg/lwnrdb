package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
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
    public void test_non_object_intermediate_resolves_to_absent() {
        final var obj = nested();
        assertFalse(JsonUtils.hasInPath(obj, "a.scalar.b"),
                "a path that walks through a scalar names nothing and must not fall back to its parent");
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(obj, "a.scalar.b"));
        assertFalse(JsonUtils.hasInPath(obj, "a.scalar.scalar"));
        assertFalse(JsonUtils.hasInPath(obj, "a.scalar.nope"));
        assertTrue(JsonUtils.hasInPath(obj, "a.scalar"), "the scalar itself still resolves");
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

    @Test
    public void test_set_path_creates_missing_parents() {
        final var obj = new JsonObject();
        JsonUtils.setPath(obj, "x.y.z", new JsonString("v"));
        assertEquals("v", obj.get("x").asJsonObject().get("y").asJsonObject().get("z").asJsonString().getValue());
        assertFalse(obj.has("x.y.z"));
    }

    @Test
    public void test_set_path_merges_into_an_existing_object() {
        final var obj = nested();
        JsonUtils.setPath(obj, "a.b.added", new JsonString("new"));
        assertEquals("deep", JsonUtils.getFromPath(obj, "a.b.c").asJsonString().getValue());
        assertEquals("new", JsonUtils.getFromPath(obj, "a.b.added").asJsonString().getValue());
    }

    @Test
    public void test_set_path_replaces_a_non_object_intermediate() {
        final var obj = nested();
        JsonUtils.setPath(obj, "a.scalar.inner", new JsonString("v"));
        assertEquals("v", JsonUtils.getFromPath(obj, "a.scalar.inner").asJsonString().getValue());
    }

    @Test
    public void test_set_path_round_trips_through_resolve_path() {
        for (final var path : List.of("a", "a.b", "a.b.c", "a.", "a..b", "a.b.", ".a")) {
            final var obj = new JsonObject();
            final var value = new JsonString(path);
            JsonUtils.setPath(obj, path, value);
            assertEquals(value, JsonUtils.resolvePath(obj, path), path);
        }
    }

    @Test
    public void test_set_path_of_a_null_value_stores_json_null() {
        final var obj = new JsonObject();
        JsonUtils.setPath(obj, "a.b", null);
        assertEquals(JsonNull.INSTANCE, JsonUtils.resolvePath(obj, "a.b"));
    }

    @Test
    public void test_set_path_of_only_dots_is_a_no_op() {
        final var obj = new JsonObject();
        JsonUtils.setPath(obj, "..", new JsonString("v"));
        assertTrue(obj.isEmpty());
    }

    @Test
    public void test_remove_path_removes_the_nested_key_only() {
        final var obj = nested();
        JsonUtils.removePath(obj, "a.b.c");
        assertFalse(JsonUtils.hasInPath(obj, "a.b.c"));
        assertTrue(JsonUtils.hasInPath(obj, "a.b.nullLeaf"));
        assertTrue(JsonUtils.hasInPath(obj, "a.scalar"));
    }

    @Test
    public void test_remove_path_of_a_single_segment_removes_the_top_level_key() {
        final var obj = nested();
        JsonUtils.removePath(obj, "top");
        assertFalse(obj.has("top"));
    }

    @Test
    public void test_remove_path_through_a_scalar_or_missing_parent_is_a_no_op() {
        final var obj = nested();
        final var before = obj.deepCopy();
        JsonUtils.removePath(obj, "a.scalar.inner");
        JsonUtils.removePath(obj, "missing.inner");
        JsonUtils.removePath(obj, "...");
        assertEquals(before, obj);
    }
}
