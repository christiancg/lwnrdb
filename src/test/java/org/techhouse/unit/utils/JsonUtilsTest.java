package org.techhouse.unit.utils;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public class JsonUtilsTest {
    // hasInPath returns true for existing nested path in JsonObject
    @Test
    public void test_has_in_path_returns_true_for_nested_path() {
        JsonObject innerObj = new JsonObject();
        innerObj.addProperty("key2", "value2");

        JsonObject obj = new JsonObject();
        obj.addProperty("key1", "value1");
        obj.add("nested", innerObj);

        boolean result = JsonUtils.hasInPath(obj, "nested.key2");

        assertTrue(result);
    }

    // Empty path string handling
    @Test
    public void test_has_in_path_with_empty_path() {
        JsonObject obj = new JsonObject();
        obj.addProperty("key1", "value1");

        boolean result = JsonUtils.hasInPath(obj, "");

        assertFalse(result);
    }

    // Returns correct JsonBaseElement when accessing single-level path
    @Test
    public void test_get_single_level_path() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", "John");

        JsonBaseElement result = JsonUtils.getFromPath(jsonObject, "name");

        assertNotNull(result);
        assertTrue(result.isJsonString());
        assertEquals("John", result.asJsonString().getValue());
    }

    // Returns JsonNull.INSTANCE when input JsonObject is null
    @Test
    public void test_get_multi_level_path() {
        JsonObject innerObj = new JsonObject();
        innerObj.addProperty("second", "value");
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("first", innerObj);
        JsonBaseElement result = JsonUtils.getFromPath(jsonObject, "first.second");

        assertNotNull(result);
        assertEquals("value", result.asJsonString().getValue());
    }

    // Compare two JsonObjects with same primitive type (string) at given field path
    @Test
    public void test_compare_string_fields() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("name", "Alice");

        JsonObject obj2 = new JsonObject();
        obj2.addProperty("name", "Bob");

        int result = JsonUtils.sortFunctionAscending(obj1, obj2, "name");

        assertTrue(result < 0);
    }

    // Compare JsonObjects where one has null value and other has non-null value
    @Test
    public void test_compare_null_and_nonnull() {
        JsonObject obj1 = new JsonObject();
        obj1.add("name", JsonNull.INSTANCE);

        JsonObject obj2 = new JsonObject();
        obj2.addProperty("name", "Bob");

        int result = JsonUtils.sortFunctionAscending(obj1, obj2, "name");

        assertEquals(1, result);
    }

    // Compare two JsonObjects with string values in descending order
    @Test
    public void test_string_values_descending_order() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("name", "Alice");

        JsonObject obj2 = new JsonObject();
        obj2.addProperty("name", "Bob");

        int result = JsonUtils.sortFunctionDescending(obj1, obj2, "name");

        assertTrue(result > 0);
    }

    // Compare when one field is JsonNull and other is not
    @Test
    public void test_one_field_null() {
        JsonObject obj1 = new JsonObject();
        obj1.add("name", JsonNull.INSTANCE);

        JsonObject obj2 = new JsonObject();
        obj2.addProperty("name", "Test");

        int result = JsonUtils.sortFunctionDescending(obj1, obj2, "name");

        assertEquals(1, result);
    }

    // sortFunctionAscending: both fields null returns 0
    @Test
    public void test_sort_ascending_both_fields_missing_returns_zero() {
        JsonObject obj1 = new JsonObject();
        JsonObject obj2 = new JsonObject();
        assertEquals(0, JsonUtils.sortFunctionAscending(obj1, obj2, "missing"));
    }

    // sortFunctionAscending: second field missing returns -1
    @Test
    public void test_sort_ascending_second_field_missing_returns_negative() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("name", "Alice");
        JsonObject obj2 = new JsonObject();
        assertTrue(JsonUtils.sortFunctionAscending(obj1, obj2, "name") < 0);
    }

    // sortFunctionAscending: first field is non-primitive (JsonObject) returns -1
    @Test
    public void test_sort_ascending_first_non_primitive_returns_negative() {
        JsonObject inner = new JsonObject();
        inner.addProperty("x", 1);
        JsonObject obj1 = new JsonObject();
        obj1.add("field", inner);
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field", "hello");
        assertTrue(JsonUtils.sortFunctionAscending(obj1, obj2, "field") < 0);
    }

    // sortFunctionAscending: first primitive, second non-primitive returns 1
    @Test
    public void test_sort_ascending_first_primitive_second_non_primitive_returns_positive() {
        JsonObject inner = new JsonObject();
        inner.addProperty("x", 1);
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field", "hello");
        JsonObject obj2 = new JsonObject();
        obj2.add("field", inner);
        assertTrue(JsonUtils.sortFunctionAscending(obj1, obj2, "field") > 0);
    }

    // Different types still order deterministically: numbers rank before strings.
    @Test
    public void test_sort_ascending_mixed_primitive_types_returns_positive() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field", "hello");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field", 42);
        assertEquals(1, JsonUtils.sortFunctionAscending(obj1, obj2, "field"));
    }

    // Booleans order the conventional way ascending - false before true - and two equal ones compare
    // equal, which a comparator has to guarantee before TimSort will accept it.
    @Test
    public void test_sort_ascending_boolean_fields() {
        JsonObject yes = new JsonObject();
        yes.add("flag", new JsonBoolean(true));
        JsonObject no = new JsonObject();
        no.add("flag", new JsonBoolean(false));
        assertTrue(JsonUtils.sortFunctionAscending(no, yes, "flag") < 0);
        assertTrue(JsonUtils.sortFunctionAscending(yes, no, "flag") > 0);
        assertEquals(0, JsonUtils.sortFunctionAscending(yes, yes, "flag"));
        assertEquals(0, JsonUtils.sortFunctionAscending(no, no, "flag"));
    }

    // sortFunctionAscending: custom type fields
    @Test
    public void test_sort_ascending_custom_type_fields() {
        JsonObject obj1 = new JsonObject();
        obj1.add("t", new JsonTime("#time(09:00:00)"));
        JsonObject obj2 = new JsonObject();
        obj2.add("t", new JsonTime("#time(10:00:00)"));
        assertTrue(JsonUtils.sortFunctionAscending(obj1, obj2, "t") < 0);
        assertEquals(0, JsonUtils.sortFunctionAscending(obj1, obj1, "t"));
    }

    // sortFunctionDescending: both fields missing returns 0
    @Test
    public void test_sort_descending_both_fields_missing_returns_zero() {
        JsonObject obj1 = new JsonObject();
        JsonObject obj2 = new JsonObject();
        assertEquals(0, JsonUtils.sortFunctionDescending(obj1, obj2, "missing"));
    }

    // sortFunctionDescending: second field missing returns -1
    @Test
    public void test_sort_descending_second_field_missing_returns_negative() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("name", "Alice");
        JsonObject obj2 = new JsonObject();
        assertTrue(JsonUtils.sortFunctionDescending(obj1, obj2, "name") < 0);
    }

    // sortFunctionDescending: first field is non-primitive returns -1
    @Test
    public void test_sort_descending_first_non_primitive_returns_negative() {
        JsonObject inner = new JsonObject();
        inner.addProperty("x", 1);
        JsonObject obj1 = new JsonObject();
        obj1.add("field", inner);
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field", "hello");
        assertTrue(JsonUtils.sortFunctionDescending(obj1, obj2, "field") < 0);
    }

    // sortFunctionDescending: mixed primitive types returns 1
    @Test
    public void test_sort_descending_mixed_primitive_types_inverts_ascending() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field", "hello");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field", 42);
        assertEquals(1, JsonUtils.sortFunctionAscending(obj1, obj2, "field"));
        assertEquals(-1, JsonUtils.sortFunctionDescending(obj1, obj2, "field"));
    }

    @Test
    public void test_sort_descending_boolean_fields() {
        JsonObject yes = new JsonObject();
        yes.add("flag", new JsonBoolean(true));
        JsonObject no = new JsonObject();
        no.add("flag", new JsonBoolean(false));
        assertTrue(JsonUtils.sortFunctionDescending(yes, no, "flag") < 0);
        assertTrue(JsonUtils.sortFunctionDescending(no, yes, "flag") > 0);
        assertEquals(0, JsonUtils.sortFunctionDescending(yes, yes, "flag"));
    }

    // sortFunctionDescending: custom type fields
    @Test
    public void test_sort_descending_custom_type_fields() {
        JsonObject obj1 = new JsonObject();
        obj1.add("t", new JsonTime("#time(09:00:00)"));
        JsonObject obj2 = new JsonObject();
        obj2.add("t", new JsonTime("#time(10:00:00)"));
        assertTrue(JsonUtils.sortFunctionDescending(obj1, obj2, "t") > 0);
    }

    // sortFunctionDescending: numbers in reverse order
    @Test
    public void test_sort_descending_numeric_fields() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("score", 80);
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("score", 40);
        assertTrue(JsonUtils.sortFunctionDescending(obj1, obj2, "score") < 0);
    }

    // hasInPath returns false when an intermediate step is a primitive, not an object
    @Test
    public void test_has_in_path_intermediate_is_primitive() {
        JsonObject obj = new JsonObject();
        obj.addProperty("level1", "not_an_object");
        assertFalse(JsonUtils.hasInPath(obj, "level1.level2"));
    }

    // getFromPath returns JsonNull when path does not exist
    @Test
    public void test_get_from_path_missing_key_returns_json_null() {
        JsonObject obj = new JsonObject();
        obj.addProperty("existing", "value");
        assertEquals(JsonNull.INSTANCE, JsonUtils.getFromPath(obj, "missing"));
    }

    // sortFunctionDescending: first is primitive, second is non-primitive (object) returns 1 (L98)
    @Test
    public void test_sort_descending_first_primitive_second_non_primitive_returns_positive() {
        JsonObject inner = new JsonObject();
        inner.addProperty("x", 1);
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field", "hello");
        JsonObject obj2 = new JsonObject();
        obj2.add("field", inner);
        assertTrue(JsonUtils.sortFunctionDescending(obj1, obj2, "field") > 0);
    }

    // canonicalize: object member order does not matter (object equality is key-order independent)
    @Test
    public void test_canonicalize_sorts_object_keys() {
        JsonObject a = new JsonObject();
        a.addProperty("b", "2");
        a.addProperty("a", "1");
        JsonObject b = new JsonObject();
        b.addProperty("a", "1");
        b.addProperty("b", "2");
        assertEquals(JsonUtils.canonicalize(a), JsonUtils.canonicalize(b));
        assertEquals(JsonUtils.hashElement(a), JsonUtils.hashElement(b));
    }

    // canonicalize: array element order matters (array equality is order dependent)
    @Test
    public void test_canonicalize_preserves_array_order() {
        JsonArray a = new JsonArray();
        a.add("x");
        a.add("y");
        JsonArray b = new JsonArray();
        b.add("y");
        b.add("x");
        assertNotEquals(JsonUtils.canonicalize(a), JsonUtils.canonicalize(b));
        assertNotEquals(JsonUtils.hashElement(a), JsonUtils.hashElement(b));
    }

    // canonicalize: integral numbers normalize so 1 and 1.0 hash equal while 1.5 differs
    @Test
    public void test_canonicalize_normalizes_integral_numbers() {
        JsonObject intObj = new JsonObject();
        intObj.add("n", new JsonNumber(1));
        JsonObject doubleObj = new JsonObject();
        doubleObj.add("n", new JsonNumber(1.0));
        JsonObject otherObj = new JsonObject();
        otherObj.add("n", new JsonNumber(1.5));
        assertEquals(JsonUtils.hashElement(intObj), JsonUtils.hashElement(doubleObj));
        assertNotEquals(JsonUtils.hashElement(intObj), JsonUtils.hashElement(otherObj));
    }

    // hashElement: equal nested values hash equal; different values differ
    @Test
    public void test_hash_element_stable_for_equal_nested_values() {
        JsonObject nestedA = new JsonObject();
        JsonArray innerA = new JsonArray();
        innerA.add("a");
        innerA.add("b");
        nestedA.add("list", innerA);
        nestedA.addProperty("flag", Boolean.TRUE);

        JsonObject nestedB = new JsonObject();
        nestedB.addProperty("flag", Boolean.TRUE);
        JsonArray innerB = new JsonArray();
        innerB.add("a");
        innerB.add("b");
        nestedB.add("list", innerB);

        assertEquals(JsonUtils.hashElement(nestedA), JsonUtils.hashElement(nestedB));

        JsonObject different = new JsonObject();
        different.addProperty("flag", Boolean.FALSE);
        assertNotEquals(JsonUtils.hashElement(nestedA), JsonUtils.hashElement(different));
    }

    // hashElement: produces a 64-char hex string; types are disambiguated and null handled
    @Test
    public void test_hash_element_format_and_type_disambiguation() {
        JsonObject obj = new JsonObject();
        obj.addProperty("k", "v");
        final var hash = JsonUtils.hashElement(obj);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
        // A string "true" must not canonicalize to the boolean true
        JsonObject strObj = new JsonObject();
        strObj.add("v", new JsonString("true"));
        JsonObject boolObj = new JsonObject();
        boolObj.add("v", new JsonBoolean(true));
        assertNotEquals(JsonUtils.hashElement(strObj), JsonUtils.hashElement(boolObj));
        assertEquals("null", JsonUtils.canonicalize(JsonNull.INSTANCE));
    }

    // canonicalize: empty object and empty array are valid and distinct
    @Test
    public void test_canonicalize_empty_object_and_array() {
        assertEquals("{}", JsonUtils.canonicalize(new JsonObject()));
        assertEquals("[]", JsonUtils.canonicalize(new JsonArray()));
        assertNotEquals(JsonUtils.hashElement(new JsonObject()), JsonUtils.hashElement(new JsonArray()));
    }
    // Over values the comparator actually orders, the two directions are exact inverses - which is
    // what stops one of them drifting from the other.
    @Test
    public void test_ascending_and_descending_are_exact_inverses_over_primitives() {
        final var values = primitiveSamples();
        for (final var a : values) {
            for (final var b : values) {
                assertEquals(Integer.signum(JsonUtils.sortFunctionAscending(a, b, "field")),
                        Integer.signum(JsonUtils.sortFunctionDescending(b, a, "field")),
                        "ascending(a,b) and descending(b,a) disagree");
            }
        }
    }

    // The deliberate exception to that inversion: a missing field sorts last whichever way you sort,
    // rather than jumping to the front when the direction flips. Only the primitive comparison
    // reverses - see compareAtPath.
    @Test
    public void test_a_missing_field_sorts_last_in_both_directions() {
        final var present = field(new JsonNumber(42));
        final var missing = new JsonObject();

        assertTrue(JsonUtils.sortFunctionAscending(missing, present, "field") > 0);
        assertTrue(JsonUtils.sortFunctionDescending(missing, present, "field") > 0);
        assertTrue(JsonUtils.sortFunctionAscending(present, missing, "field") < 0);
        assertTrue(JsonUtils.sortFunctionDescending(present, missing, "field") < 0);
        assertEquals(0, JsonUtils.sortFunctionAscending(missing, new JsonObject(), "field"));
    }

    // A comparator that is not antisymmetric makes TimSort throw "Comparison method violates its
    // general contract!" on a long enough list, which would fail the whole SORT step.
    @Test
    public void test_the_comparator_is_a_valid_total_order() {
        final var values = sortSamples();
        for (final var a : values) {
            assertEquals(0, JsonUtils.sortFunctionAscending(a, a, "field"), "a value must equal itself");
            for (final var b : values) {
                assertEquals(Integer.signum(JsonUtils.sortFunctionAscending(a, b, "field")),
                        -Integer.signum(JsonUtils.sortFunctionAscending(b, a, "field")),
                        "compare(a,b) and compare(b,a) must have opposite signs");
            }
        }
        final var many = new ArrayList<JsonObject>();
        for (var i = 0; i < 40; i++) {
            many.addAll(values);
        }
        assertDoesNotThrow(() -> many.sort((o1, o2) -> JsonUtils.sortFunctionAscending(o1, o2, "field")));
    }

    private static List<JsonObject> primitiveSamples() {
        final var samples = new ArrayList<JsonObject>();
        samples.add(field(new JsonBoolean(true)));
        samples.add(field(new JsonBoolean(false)));
        samples.add(field(new JsonNumber(42)));
        samples.add(field(new JsonNumber(7)));
        samples.add(field(new JsonString("hello")));
        samples.add(field(new JsonString("world")));
        samples.add(field(new JsonDateTime("#datetime(2023-10-01T10:00:00)")));
        samples.add(field(new JsonTime("#time(10:00:00)")));
        return samples;
    }

    // Everything above plus the shapes the comparator refuses to order: a nested object and a
    // missing field. TimSort sees these too, so they belong in the total-order check.
    private static List<JsonObject> sortSamples() {
        final var samples = primitiveSamples();
        samples.add(field(new JsonObject()));
        samples.add(new JsonObject());
        return samples;
    }

    private static JsonObject field(JsonBaseElement value) {
        final var object = new JsonObject();
        object.add("field", value);
        return object;
    }
}
