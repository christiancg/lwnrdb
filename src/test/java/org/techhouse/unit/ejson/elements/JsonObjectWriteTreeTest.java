package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.elements.WriteTree;
import org.techhouse.utils.JsonUtils;

public class JsonObjectWriteTreeTest {
    private static JsonObject nested() {
        final var c = new JsonObject();
        c.addProperty("leaf", 1);
        final var b = new JsonObject();
        b.add("c", c);
        b.addProperty("other", 2);
        final var x = new JsonObject();
        x.addProperty("y", 3);
        final var a = new JsonObject();
        a.add("b", b);
        a.add("x", x);
        final var root = new JsonObject();
        root.addProperty("_id", "r");
        root.add("a", a);
        root.add("sibling", new JsonObject());
        return root;
    }

    private static JsonObject at(JsonObject root, String path) {
        final var resolved = JsonUtils.resolvePath(root, path);
        assertNotNull(resolved);
        return resolved.asJsonObject();
    }

    @Test
    public void test_a_deep_write_copies_only_the_containers_on_its_path() {
        final var source = nested();

        final var copy = source.copyForWrites(JsonUtils.writeTreeOf(List.of("a.b.c")));

        assertNotSame(source, copy);
        assertNotSame(at(source, "a"), at(copy, "a"));
        assertNotSame(at(source, "a.b"), at(copy, "a.b"));
        assertSame(at(source, "a.b.c"), at(copy, "a.b.c"));
        assertSame(at(source, "a.x"), at(copy, "a.x"));
        assertSame(source.get("sibling"), copy.get("sibling"));
        assertEquals(source, copy);
    }

    @Test
    public void test_a_removed_root_is_never_copied() {
        final var large = new JsonArray();
        for (var i = 0; i < 1000; i++) {
            large.add(new JsonString("item" + i));
        }
        final var source = new JsonObject();
        source.add("group", large);
        source.add("kept", new JsonObject());

        final var copy = source.copyForWrites(JsonUtils.writeTreeOf(List.of("group")));
        JsonUtils.removePath(copy, "group");

        assertSame(large, source.get("group"));
        assertFalse(copy.has("group"));
        assertSame(source.get("kept"), copy.get("kept"));
    }

    @Test
    public void test_writes_through_a_copy_leave_the_source_unchanged() {
        final var source = nested();
        final var before = source.deepCopy();
        final var paths = List.of("a.b.c.leaf", "a.b.other", "sibling", "a.new.deep");

        final var copy = source.copyForWrites(JsonUtils.writeTreeOf(paths));
        JsonUtils.setPath(copy, "a.b.c.leaf", new JsonNumber(9));
        JsonUtils.removePath(copy, "a.b.other");
        JsonUtils.removePath(copy, "sibling");
        JsonUtils.setPath(copy, "a.new.deep", new JsonNumber(5));

        assertEquals(before, source);
        assertEquals(9, at(copy, "a.b.c").get("leaf").asJsonNumber().getValue().intValue());
        assertFalse(at(copy, "a.b").has("other"));
        assertTrue(at(copy, "a.new").has("deep"));
        assertSame(at(source, "a.x"), at(copy, "a.x"));
    }

    @Test
    public void test_a_write_through_a_non_object_replaces_it_without_touching_the_source() {
        final var array = new JsonArray();
        array.add(new JsonNumber(1));
        final var source = new JsonObject();
        source.add("a", array);

        final var copy = source.copyForWrites(JsonUtils.writeTreeOf(List.of("a.b")));
        JsonUtils.setPath(copy, "a.b", new JsonNumber(2));

        assertSame(array, source.get("a"));
        assertEquals(1, array.size());
        assertEquals(2, at(copy, "a").get("b").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_a_write_tree_merges_shared_prefixes_and_skips_blank_paths() {
        final var paths = new ArrayList<String>();
        paths.add("a.b");
        paths.add("a.c");
        paths.add(null);
        paths.add("...");

        final var tree = JsonUtils.writeTreeOf(paths);

        assertTrue(tree.hasChildren());
        assertTrue(tree.child("a").hasChildren());
        assertFalse(tree.child("a").child("b").hasChildren());
        assertFalse(tree.child("a").child("c").hasChildren());
        assertNull(tree.child("b"));
        assertFalse(WriteTree.of(List.of()).hasChildren());
    }
}
