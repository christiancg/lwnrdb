package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public class JsonUtilsCoverageTest {

    private static JsonObject sample() {
        final var obj = new JsonObject();
        obj.add("bool", new JsonBoolean(true));
        obj.add("intNum", new JsonNumber(42));
        obj.add("realNum", new JsonNumber(3.5));
        obj.add("str", new JsonString("a\"b\\c"));
        obj.add("nul", JsonNull.INSTANCE);
        final var arr = new JsonArray();
        arr.add(new JsonNumber(1));
        arr.add(new JsonString("y"));
        obj.add("arr", arr);
        return obj;
    }

    @Test
    public void test_canonicalize_covers_all_element_types() {
        final var canonical = JsonUtils.canonicalize(sample());
        assertNotNull(canonical);
        // Integer-valued doubles normalize without a decimal point; reals keep it.
        assertTrue(canonical.contains("42"));
        assertTrue(canonical.contains("3.5"));
    }

    @Test
    public void test_hash_element_is_stable_and_hex() {
        final var h1 = JsonUtils.hashElement(sample());
        final var h2 = JsonUtils.hashElement(sample());
        assertEquals(h1, h2);
        assertTrue(h1.matches("[0-9a-f]+"));
    }

    @Test
    public void test_has_in_path_and_get_from_path() {
        final var root = new JsonObject();
        final var nested = new JsonObject();
        nested.add("leaf", new JsonString("v"));
        root.add("branch", nested);
        assertTrue(JsonUtils.hasInPath(root, "branch.leaf"));
        assertFalse(JsonUtils.hasInPath(root, "branch.missing"));
        assertEquals("v", JsonUtils.getFromPath(root, "branch.leaf").asJsonString().getValue());
    }

    @Test
    public void test_sort_functions_ascending_and_descending() {
        final var a = new JsonObject();
        a.add("n", new JsonNumber(1));
        final var b = new JsonObject();
        b.add("n", new JsonNumber(2));
        assertTrue(JsonUtils.sortFunctionAscending(a, b, "n") < 0);
        assertTrue(JsonUtils.sortFunctionDescending(a, b, "n") > 0);
    }

    @Test
    public void test_negative_zero_sorts_equal_to_positive_zero() {
        final var negative = new JsonNumber(-0.0);
        final var positive = new JsonNumber(0.0);

        assertEquals(0, JsonUtils.compareSortKeysAscending(negative, positive),
                "the index buckets both spellings under one key, so a scan that separates them makes the"
                        + " same query answer differently with and without an index");
        assertEquals(0, JsonUtils.compareSortKeysDescending(negative, positive));
    }

    @Test
    public void test_an_integer_sorts_equal_to_the_same_value_as_a_double() {
        assertEquals(0, JsonUtils.compareSortKeysAscending(new JsonNumber(2), new JsonNumber(2.0)));
    }

    @Test
    public void test_numeric_order_survives_the_normalisation() {
        assertTrue(JsonUtils.compareSortKeysAscending(new JsonNumber(-1.5), new JsonNumber(-0.0)) < 0);
        assertTrue(JsonUtils.compareSortKeysAscending(new JsonNumber(-0.0), new JsonNumber(1.5)) < 0);
        assertTrue(JsonUtils.compareSortKeysDescending(new JsonNumber(1.5), new JsonNumber(-1.5)) < 0);
    }
}
