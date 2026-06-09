package org.techhouse.unit.utils;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonTime;
import org.techhouse.ejson.elements.*;
import org.techhouse.utils.JsonUtils;

import static org.junit.jupiter.api.Assertions.*;

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

    // sortFunctionAscending: mixed primitive types (string vs number) returns 1
    @Test
    public void test_sort_ascending_mixed_primitive_types_returns_positive() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field", "hello");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field", 42);
        assertEquals(1, JsonUtils.sortFunctionAscending(obj1, obj2, "field"));
    }

    // sortFunctionAscending: boolean fields
    @Test
    public void test_sort_ascending_boolean_fields() {
        JsonObject obj1 = new JsonObject();
        obj1.add("flag", new JsonBoolean(true));
        JsonObject obj2 = new JsonObject();
        obj2.add("flag", new JsonBoolean(false));
        assertTrue(JsonUtils.sortFunctionAscending(obj1, obj2, "flag") < 0);
        assertTrue(JsonUtils.sortFunctionAscending(obj2, obj1, "flag") > 0);
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
    public void test_sort_descending_mixed_primitive_types_returns_positive() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("field", "hello");
        JsonObject obj2 = new JsonObject();
        obj2.addProperty("field", 42);
        assertEquals(1, JsonUtils.sortFunctionDescending(obj1, obj2, "field"));
    }

    // sortFunctionDescending: boolean fields — code returns o2.isTrue ? 1 : -1
    @Test
    public void test_sort_descending_boolean_fields() {
        JsonObject obj1 = new JsonObject();
        obj1.add("flag", new JsonBoolean(true));
        JsonObject obj2 = new JsonObject();
        obj2.add("flag", new JsonBoolean(false));
        // o2=false → false ? 1 : -1 = -1
        assertTrue(JsonUtils.sortFunctionDescending(obj1, obj2, "flag") < 0);
        // o2=true → true ? 1 : -1 = 1
        assertTrue(JsonUtils.sortFunctionDescending(obj2, obj1, "flag") > 0);
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
}