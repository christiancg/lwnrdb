package org.techhouse.unit.utils;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
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
}