package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.FieldPath;
import org.techhouse.utils.FieldPathMemo;
import org.techhouse.utils.JsonUtils;

public class FieldPathTest {
    private static final List<String> PATHS = List.of("", ".", "..", "...", "a", "a.", "a..", ".a", "..a", "a..b",
            "a.b", "a.b.", "a.b..", "a.b.c", "a.b.c.", "a..b..", "a.b.c.d", "a.nope.c", "a.scalar", "a.scalar.b",
            "a.nullLeaf.b", "a.nullLeaf", "tags", "tags.0", "tags.x", "list.0.k", "_id", "_id.", "_id..", "._id",
            "_id.x", "x._id", "empty", "empty.", ".empty", "a..", "a.b.c.d.e.f", "deep.x.y.z.w", "nested.");

    private static final List<String> DOCUMENTS = List.of("{}", "{\"_id\":\"k1\"}",
            "{\"_id\":\"k2\",\"a\":{\"b\":{\"c\":7,\"d\":{\"e\":{\"f\":true}}},\"scalar\":3,\"nullLeaf\":null}}",
            "{\"a\":{\"\":{\"b\":\"empty-key\"},\"b\":[1,2]},\"\":{\"a\":\"root-empty\",\"\":1}}",
            "{\"a\":null,\"tags\":[\"x\",\"y\"],\"list\":[{\"k\":1}]}", "{\"a\":\"text\",\"_id\":\"k3\"}",
            "{\"a\":{\"b\":{\"c\":{\"x\":1}}},\"empty\":{}}",
            "{\"deep\":{\"x\":{\"y\":{\"z\":{\"w\":[{\"q\":1}]}}}},\"nested\":{\"v\":2}}");

    private static List<JsonObject> documents() {
        final var parsed = new ArrayList<JsonObject>();
        for (final var json : DOCUMENTS) {
            parsed.add(new EJson().fromJson(json, JsonObject.class));
        }
        return parsed;
    }

    private static List<String> legacySegments(String path) {
        if (path.indexOf('.') < 0) {
            return List.of(path);
        }
        final var stripped = JsonUtils.stripTrailingDots(path);
        return stripped.isEmpty() ? List.of() : List.of(stripped.split("\\.", -1));
    }

    private static JsonBaseElement legacyResolve(JsonObject obj, String path) {
        if (path.indexOf('.') < 0) {
            return obj.get(path);
        }
        final var segments = legacySegments(path);
        if (segments.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonBaseElement current = obj;
        for (final var segment : segments) {
            if (!current.isJsonObject()) {
                return null;
            }
            current = current.asJsonObject().get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static void legacySet(JsonObject obj, String path, JsonBaseElement value) {
        final var segments = legacySegments(path);
        if (segments.isEmpty()) {
            return;
        }
        var parent = obj;
        for (final var segment : segments.subList(0, segments.size() - 1)) {
            final var child = parent.get(segment);
            if (child != null && child.isJsonObject()) {
                parent = child.asJsonObject();
            } else {
                final var created = new JsonObject();
                parent.add(segment, created);
                parent = created;
            }
        }
        parent.add(segments.getLast(), value);
    }

    private static void legacyRemove(JsonObject obj, String path) {
        final var segments = legacySegments(path);
        if (segments.isEmpty()) {
            return;
        }
        var parent = obj;
        for (final var segment : segments.subList(0, segments.size() - 1)) {
            final var child = parent.get(segment);
            if (child == null || !child.isJsonObject()) {
                return;
            }
            parent = child.asJsonObject();
        }
        parent.remove(segments.getLast());
    }

    @Test
    public void test_segments_follow_the_legacy_grammar_for_every_path() {
        for (final var path : PATHS) {
            assertEquals(legacySegments(path), FieldPath.of(path).segments(), path);
        }
    }

    @Test
    public void test_trailing_dots_are_dropped_and_interior_empty_segments_kept() {
        assertEquals(List.of("a"), FieldPath.of("a..").segments());
        assertEquals(List.of("a", "", "b"), FieldPath.of("a..b").segments());
        assertEquals(List.of("", "a"), FieldPath.of(".a").segments());
        assertEquals(List.of(), FieldPath.of("...").segments());
        assertEquals(List.of(""), FieldPath.of("").segments());
    }

    @Test
    public void test_resolve_returns_the_same_element_as_the_legacy_walk() {
        for (final var document : documents()) {
            for (final var path : PATHS) {
                final var expected = legacyResolve(document, path);
                assertSame(expected, FieldPath.of(path).resolve(document), path + " on " + document);
                assertSame(expected, JsonUtils.resolvePath(document, path), path + " on " + document);
            }
        }
    }

    @Test
    public void test_value_and_presence_match_get_from_path_and_has_in_path() {
        for (final var document : documents()) {
            for (final var path : PATHS) {
                final var legacy = legacyResolve(document, path);
                final var fieldPath = FieldPath.of(path);
                assertSame(legacy == null ? JsonNull.INSTANCE : legacy, fieldPath.valueIn(document), path);
                assertEquals(legacy != null, fieldPath.isIn(document), path);
                assertSame(fieldPath.valueIn(document), JsonUtils.getFromPath(document, path), path);
                assertEquals(fieldPath.isIn(document), JsonUtils.hasInPath(document, path), path);
            }
        }
    }

    @Test
    public void test_a_path_of_only_dots_resolves_to_null_rather_than_absent() {
        final var document = documents().get(2);
        assertSame(JsonNull.INSTANCE, FieldPath.of("..").resolve(document));
        assertTrue(FieldPath.of("..").isIn(document));
    }

    @Test
    public void test_set_writes_what_the_legacy_writer_wrote() {
        for (final var document : documents()) {
            for (final var path : PATHS) {
                final var expected = document.deepCopy();
                legacySet(expected, path, new JsonString("v"));
                final var actual = document.deepCopy();
                FieldPath.of(path).set(actual, new JsonString("v"));
                assertEquals(expected, actual, path + " on " + document);
                final var viaUtils = document.deepCopy();
                JsonUtils.setPath(viaUtils, path, new JsonString("v"));
                assertEquals(expected, viaUtils, path + " on " + document);
            }
        }
    }

    @Test
    public void test_remove_deletes_what_the_legacy_remover_deleted() {
        for (final var document : documents()) {
            for (final var path : PATHS) {
                final var expected = document.deepCopy();
                legacyRemove(expected, path);
                final var actual = document.deepCopy();
                FieldPath.of(path).remove(actual);
                assertEquals(expected, actual, path + " on " + document);
                final var viaUtils = document.deepCopy();
                JsonUtils.removePath(viaUtils, path);
                assertEquals(expected, viaUtils, path + " on " + document);
            }
        }
    }

    @Test
    public void test_primary_key_matches_is_primary_key_path() {
        for (final var path : PATHS) {
            assertEquals(JsonUtils.isPrimaryKeyPath(path), FieldPath.of(path).isPrimaryKey(), path);
        }
        assertTrue(FieldPath.of("_id").isPrimaryKey());
        assertTrue(FieldPath.of("_id..").isPrimaryKey());
        assertFalse(FieldPath.of("_ID").isPrimaryKey());
        assertFalse(FieldPath.of("x._id").isPrimaryKey());
    }

    @Test
    public void test_the_id_path_resolves_the_stored_key() {
        final var document = documents().get(1);
        assertEquals(new JsonString("k1"), FieldPath.of("_id").resolve(document));
        assertEquals(new JsonString("k1"), FieldPath.of("_id.").resolve(document));
        assertNull(FieldPath.of("_id.x").resolve(document));
    }

    @Test
    public void test_missing_intermediates_and_arrays_along_the_path_resolve_to_absent() {
        final var document = documents().get(4);
        assertNull(FieldPath.of("a.b").resolve(document), "through a stored null");
        assertNull(FieldPath.of("tags.0").resolve(document), "arrays are not indexed by position");
        assertNull(FieldPath.of("list.0.k").resolve(document));
        assertNull(FieldPath.of("missing.b").resolve(document));
        assertInstanceOf(JsonArray.class, FieldPath.of("tags").resolve(document),
                "a path ending at an array returns it");
    }

    @Test
    public void test_reused_for_keeps_the_compiled_path_only_for_the_same_text() {
        final var compiled = FieldPath.of("a.b");
        assertSame(compiled, compiled.reusedFor("a.b"));
        assertSame(compiled, compiled.reusedFor(String.join(".", "a", "b")));
        final var other = compiled.reusedFor("a.c");
        assertNotSame(compiled, other);
        assertEquals("a.c", other.text());
        assertEquals(List.of("a", "c"), other.segments());
    }

    @Test
    public void test_memo_compiles_each_text_once() {
        final var memo = new FieldPathMemo();
        final var first = memo.of("x.y");
        assertSame(first, memo.of(String.join(".", "x", "y")));
        assertNotSame(first, memo.of("x.z"));
        assertEquals("x.y", first.text());
    }
}
