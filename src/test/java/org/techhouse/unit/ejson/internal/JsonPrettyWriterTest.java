package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.JsonPrettyWriter;

public class JsonPrettyWriterTest {
    private static final EJson EJSON = new EJson();

    private static JsonObject sample() {
        final var object = new JsonObject();
        object.add("n", new JsonNumber(1));
        final var inner = new JsonObject();
        inner.add("k", JsonNull.INSTANCE);
        final var array = new JsonArray();
        array.add(new JsonNumber(2));
        array.add(inner);
        object.add("list", array);
        return object;
    }

    // Two-space indentation nests objects and arrays
    @Test
    public void test_two_space_indent() {
        assertEquals("""
                {
                  "n": 1,
                  "list": [
                    2,
                    {
                      "k": null
                    }
                  ]
                }""", JsonPrettyWriter.toJson(sample(), "  "));
    }

    // The indent string is used verbatim
    @Test
    public void test_tab_indent() {
        final var object = new JsonObject();
        object.add("a", new JsonNumber(1));
        assertEquals("{\n\t\"a\": 1\n}", JsonPrettyWriter.toJson(object, "\t"));
    }

    // Empty containers render compactly
    @Test
    public void test_empty_containers() {
        final var object = new JsonObject();
        object.add("o", new JsonObject());
        object.add("a", new JsonArray());
        assertEquals("{\n  \"o\": {},\n  \"a\": []\n}", JsonPrettyWriter.toJson(object, "  "));
        assertEquals("{}", JsonPrettyWriter.toJson(new JsonObject(), "  "));
        assertEquals("[]", JsonPrettyWriter.toJson(new JsonArray(), "  "));
    }

    // Keys are escaped and leaves delegate to the adapters
    @Test
    public void test_escaping_and_leaf_delegation() {
        final var object = new JsonObject();
        object.add("quo\"te", new JsonString("a\nb"));
        assertEquals("{\n  \"quo\\\"te\": \"a\\nb\"\n}", JsonPrettyWriter.toJson(object, "  "));
    }

    // Custom EJson types stay leaves
    @Test
    public void test_custom_types_are_leaves() {
        final var object = new JsonObject();
        object.add("g", CustomTypeFactory.getCustomTypeInstance(new JsonString("#geo(1.0,2.0)")));
        assertEquals("{\n  \"g\": \"#geo(1.0,2.0)\"\n}", JsonPrettyWriter.toJson(object, "  "));
    }

    // A null element renders as JSON null
    @Test
    public void test_null_element() {
        assertEquals("null", JsonPrettyWriter.toJson(null, "  "));
    }

    // A null or empty indent delegates to the compact writer, and stripping whitespace matches it
    @Test
    public void test_indent_absent_matches_compact() {
        final var ejson = EJSON;
        final var compact = ejson.toJson(sample());
        assertEquals(compact, ejson.toJson(sample(), null));
        assertEquals(compact, ejson.toJson(sample(), ""));
        assertEquals(compact, ejson.toJson(sample(), "  ").replaceAll("[\\n ]", ""));
        assertEquals("null", ejson.toJson(null, null));
    }
}
