package org.techhouse.unit.ejson.elements;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.*;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class JsonObjectTest {
    // Add string property and verify it is stored correctly
    @Test
    public void test_add_string_property_stores_value() {
        JsonObject jsonObject = new JsonObject();
        String propertyName = "name";
        String propertyValue = "John";

        jsonObject.addProperty(propertyName, propertyValue);

        JsonBaseElement element = jsonObject.get(propertyName);
        assertTrue(element.isJsonString());
        assertEquals(propertyValue, element.asJsonString().getValue());
    }

    // Add property with null key should throw NullPointerException
    @Test
    public void test_add_property_with_null_key_throws_exception() {
        JsonObject jsonObject = new JsonObject();
        String value = "test";

        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(null, value));
    }

    // Add JsonBaseElement with non-null value to JsonObject
    @Test
    public void test_add_non_null_value_to_json_object() {
        JsonObject jsonObject = new JsonObject();
        JsonString value = new JsonString("test value");
        String property = "testKey";

        jsonObject.add(property, value);

        assertEquals(value, jsonObject.get(property));
        assertFalse(jsonObject.get(property).isJsonNull());
    }

    // Add property with empty string key
    @Test
    public void test_add_with_empty_string_key() {
        JsonObject jsonObject = new JsonObject();
        JsonString value = new JsonString("test value");
        String emptyKey = "";

        jsonObject.add(emptyKey, value);

        assertEquals(value, jsonObject.get(emptyKey));
        assertEquals(1, jsonObject.size());
    }

    // Add string property and value to empty JsonObject
    @Test
    public void test_add_string_property_to_empty_object() {
        JsonObject jsonObject = new JsonObject();
        String property = "name";
        String value = "John";

        jsonObject.add(property, value);

        JsonString result = (JsonString) jsonObject.get(property);
        assertEquals(value, result.getValue());
    }

    // Add property with empty string value
    @Test
    public void test_add_property_with_empty_string_value() {
        JsonObject jsonObject = new JsonObject();
        String property = "name";
        String value = "";

        jsonObject.add(property, value);

        JsonString result = (JsonString) jsonObject.get(property);
        assertEquals(value, result.getValue());
    }

    // Add property with empty string value
    @Test
    public void test_add_property_with_empty_value() {
        JsonObject jsonObject = new JsonObject();
        String property = "emptyValue";
        String value = "";

        jsonObject.add(property, value);

        JsonString result = (JsonString) jsonObject.get(property);
        assertEquals(value, result.getValue());
    }

    // Adding a string property with non-empty value creates JsonString element
    @Test
    public void test_add_string_property_creates_json_string() {
        JsonObject jsonObject = new JsonObject();
        String propertyName = "name";
        String propertyValue = "John";

        jsonObject.addProperty(propertyName, propertyValue);

        JsonBaseElement element = jsonObject.get(propertyName);
        assertInstanceOf(JsonString.class, element);
        assertEquals(propertyValue, ((JsonString)element).getValue());
    }

    // Add Integer property with valid key and value
    @Test
    public void test_add_valid_integer_property() {
        JsonObject jsonObject = new JsonObject();

        jsonObject.addProperty("age", 25);

        JsonNumber result = (JsonNumber) jsonObject.get("age");
        assertEquals(25, result.asInteger());
    }

    // Add number property with valid key and value
    @Test
    public void test_add_valid_number_property() {
        JsonObject jsonObject = new JsonObject();

        jsonObject.addProperty("number", new Number() {
            @Override
            public int intValue() {
                return 10;
            }

            @Override
            public long longValue() {
                return 10;
            }

            @Override
            public float floatValue() {
                return 10;
            }

            @Override
            public double doubleValue() {
                return 10;
            }
        });

        JsonNumber result = (JsonNumber) jsonObject.get("number");
        assertEquals(10, result.asInteger());
    }

    @Test
    public void test_add_valid_long_property() {
        JsonObject jsonObject = new JsonObject();

        jsonObject.addProperty("number", 23L);

        JsonNumber result = (JsonNumber) jsonObject.get("number");
        assertEquals(23, result.asInteger());
    }

    // Add Integer property with null key
    @Test
    public void test_add_property_with_null_key() {
        JsonObject jsonObject = new JsonObject();

        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(null, 42));
    }

    // Add property with valid Number value creates JsonNumber and stores in members map
    @Test
    public void test_add_property_with_valid_number() {
        JsonObject jsonObject = new JsonObject();
        Integer testValue = 42;
        String propertyName = "testProp";

        jsonObject.addProperty(propertyName, testValue);

        JsonNumber storedValue = (JsonNumber) jsonObject.get(propertyName);
        assertEquals(testValue, storedValue.getValue());
        assertEquals(testValue.intValue(), storedValue.asInteger());
    }

    // Add property with null Number value creates JsonNumber with null value
    @Test
    public void test_add_property_with_null_number() {
        JsonObject jsonObject = new JsonObject();
        Number nullValue = null;
        String propertyName = "testProp";

        jsonObject.addProperty(propertyName, nullValue);

        JsonNumber storedValue = (JsonNumber) jsonObject.get(propertyName);
        assertNull(storedValue.getValue());
        assertNull(storedValue.asInteger());
    }

    // Add Long property with positive value to JsonObject
    @Test
    public void test_add_long_property_with_positive_value() {
        JsonObject jsonObject = new JsonObject();
        Long value = 123L;
        String property = "testKey";

        jsonObject.addProperty(property, value);

        JsonNumber result = (JsonNumber) jsonObject.get(property);
        assertEquals(value, result.getValue());
    }

    // Add property with null key
    @Test
    public void test_add_long_property_with_null_key() {
        JsonObject jsonObject = new JsonObject();
        Long value = 100L;
        String property = null;

        assertThrows(NullPointerException.class, () -> jsonObject.addProperty(property, value));
    }

    // Remove existing property from JsonObject
    @Test
    public void test_remove_existing_property() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", "John");
        jsonObject.addProperty("age", 30);

        jsonObject.remove("name");

        assertFalse(jsonObject.has("name"));
        assertTrue(jsonObject.has("age"));
    }

    // Remove non-existent property
    @Test
    public void test_remove_nonexistent_property() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", "John");

        jsonObject.remove("age");

        assertTrue(jsonObject.has("name"));
        assertFalse(jsonObject.has("age"));
    }

    // Return set of entries from non-empty JsonObject with multiple key-value pairs
    @Test
    public void test_entry_set_returns_all_entries() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("key1", new JsonString("value1"));
        jsonObject.add("key2", new JsonNumber(123));
        jsonObject.add("key3", new JsonBoolean(true));

        Set<Map.Entry<String, JsonBaseElement>> entries = jsonObject.entrySet();

        assertEquals(3, entries.size());
        assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("key1") && e.getValue().asJsonString().getValue().equals("value1")));
        assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("key2") && e.getValue().asJsonNumber().getValue().equals(123)));
        assertTrue(entries.stream().anyMatch(e -> e.getKey().equals("key3") && e.getValue().asJsonBoolean().getValue().equals(true)));
    }

    // Verify returned set is unmodifiable/immutable
    @Test
    public void test_entry_set_is_immutable() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("key1", new JsonString("value1"));
        Set<Map.Entry<String, JsonBaseElement>> entries = jsonObject.entrySet();

        assertThrows(UnsupportedOperationException.class, () -> entries.add(Map.entry("key2", new JsonString("value2"))));
    }

    // Return correct size for JsonObject with multiple members
    @Test
    public void test_size_with_multiple_members() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("name", "John");
        jsonObject.addProperty("age", 30);
        jsonObject.addProperty("city", "New York");

        assertEquals(3, jsonObject.size());
    }

    // Return size for JsonObject with large number of members
    @Test
    public void test_size_with_large_members() {
        JsonObject jsonObject = new JsonObject();
        for(int i = 0; i < 1000; i++) {
            jsonObject.addProperty("key" + i, i);
        }

        assertEquals(1000, jsonObject.size());
    }

    // Return true for newly created JsonObject with no members
    @Test
    public void test_empty_json_object_is_empty() {
        JsonObject jsonObject = new JsonObject();

        assertTrue(jsonObject.isEmpty());
    }

    // Return true after adding and removing same property
    @Test
    public void test_json_object_empty_after_add_remove() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("test", "value");
        jsonObject.remove("test");

        assertTrue(jsonObject.isEmpty());
    }

    // Returns true when checking for an existing member name
    @Test
    public void test_has_returns_true_for_existing_member() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("existingMember", "value");

        boolean result = jsonObject.has("existingMember");

        assertTrue(result);
    }

    // Check behavior with null member name
    @Test
    public void test_has_with_null_member_name() {
        JsonObject jsonObject = new JsonObject();

        boolean result = jsonObject.has(null);

        assertFalse(result);
    }

    // Return JsonBaseElement value when key exists in members map
    @Test
    public void test_get_returns_value_when_key_exists() {
        JsonObject jsonObject = new JsonObject();
        JsonString value = new JsonString("test value");
        jsonObject.add("key", value);

        JsonBaseElement result = jsonObject.get("key");

        assertEquals(value, result);
    }

    // Compare JsonObject with itself returns true
    @Test
    public void test_equals_same_object_returns_true() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key", "value");

        boolean result = jsonObject.equals(jsonObject);

        assertTrue(result);
    }

    // Compare JsonObject with null returns false
    @Test
    public void test_equals_null_returns_false() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key", "value");

        boolean result = jsonObject.equals(null);

        assertFalse(result);
    }

    // Returns consistent hash code for same JsonObject content
    @Test
    public void test_same_content_returns_same_hashcode() {
        JsonObject obj1 = new JsonObject();
        obj1.addProperty("name", "test");
        obj1.addProperty("value", 123);

        JsonObject obj2 = new JsonObject();
        obj2.addProperty("name", "test");
        obj2.addProperty("value", 123);

        assertEquals(obj1.hashCode(), obj2.hashCode());
    }

    // Returns valid hash code for empty JsonObject
    @Test
    public void test_empty_object_hashcode() {
        JsonObject emptyObj1 = new JsonObject();
        JsonObject emptyObj2 = new JsonObject();

        assertEquals(emptyObj1.hashCode(), emptyObj2.hashCode());
        assertTrue(emptyObj1.hashCode() >= 0);
    }

    // Deep copy creates new JsonObject instance with same key-value pairs as original
    @Test
    public void test_deep_copy_creates_equal_object() {
        JsonObject original = new JsonObject();
        original.addProperty("string", "test");
        original.addProperty("number", 42);
        original.addProperty("long", 123L);

        JsonObject copy = original.deepCopy();

        assertNotSame(original, copy);
        assertEquals(original, copy);
        assertEquals(original.get("string"), copy.get("string"));
        assertEquals(original.get("number"), copy.get("number"));
        assertEquals(original.get("long"), copy.get("long"));
    }

    // Deep copy of JsonObject containing null values
    @Test
    public void test_deep_copy_with_null_values() {
        JsonObject original = new JsonObject();
        original.add("nullValue", JsonNull.INSTANCE);
        original.add("normalValue", new JsonString("test"));

        JsonObject copy = original.deepCopy();

        assertNotSame(original, copy);
        assertEquals(original, copy);
        assertTrue(copy.has("nullValue"));
        assertEquals(JsonNull.INSTANCE, copy.get("nullValue"));
        assertEquals(new JsonString("test"), copy.get("normalValue"));
    }
}