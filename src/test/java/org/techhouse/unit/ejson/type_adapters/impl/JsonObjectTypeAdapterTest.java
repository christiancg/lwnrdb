package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.JsonObjectTypeAdapter;

import static org.junit.jupiter.api.Assertions.*;

public class JsonObjectTypeAdapterTest {
    @BeforeEach
    public void setUp() {
        new EJson();
    }

    // Convert empty JsonObject to valid JSON string "{}"
    @Test
    public void test_empty_json_object_converts_to_empty_json_string() {
        JsonObjectTypeAdapter adapter = new JsonObjectTypeAdapter();
        JsonObject emptyObject = new JsonObject();

        String result = adapter.toJson(emptyObject);

        assertEquals("{}", result);
    }

    // Return null when fromJson receives non-OBJECT JsonBaseElement
    @Test
    public void test_from_json_returns_null_for_non_object_element() {
        JsonObjectTypeAdapter adapter = new JsonObjectTypeAdapter();
        JsonString nonObjectElement = new JsonString("test");

        JsonObject result = adapter.fromJson(nonObjectElement);

        assertNull(result);
    }

    // Handle null JsonObject input
    @Test
    public void test_null_json_object_handling() {
        JsonObjectTypeAdapter adapter = new JsonObjectTypeAdapter();

        assertThrows(NullPointerException.class, () -> adapter.toJson(null));
    }

    // Convert JsonObject with single string property to valid JSON string
    @Test
    public void test_single_string_property_to_json() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key", "value");
        JsonObjectTypeAdapter adapter = new JsonObjectTypeAdapter();
        String json = adapter.toJson(jsonObject);
        assertEquals("{\"key\":\"value\"}", json);
    }

    // Convert JsonObject with multiple properties to valid JSON string with comma separation
    @Test
    public void test_multiple_properties_to_json() {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("key1", "value1");
        jsonObject.addProperty("key2", 123);
        JsonObjectTypeAdapter adapter = new JsonObjectTypeAdapter();
        String json = adapter.toJson(jsonObject);
        assertEquals("{\"key1\":\"value1\",\"key2\":123}", json);
    }

    // Convert nested JsonObject structures correctly
    @Test
    public void test_nested_json_object_to_json() {
        JsonObject innerObject = new JsonObject();
        innerObject.addProperty("innerKey", "innerValue");

        JsonObject outerObject = new JsonObject();
        outerObject.add("nested", innerObject);

        JsonObjectTypeAdapter adapter = new JsonObjectTypeAdapter();
        String json = adapter.toJson(outerObject);

        assertEquals("{\"nested\":{\"innerKey\":\"innerValue\"}}", json);
    }

    // Returns JsonObject when input value is of JsonType.OBJECT
    @Test
    public void test_returns_json_object_when_input_is_object_type() {
        JsonObject inputObject = new JsonObject();
        inputObject.addProperty("key", "value");

        JsonObjectTypeAdapter converter = new JsonObjectTypeAdapter();
        JsonObject result = converter.fromJson(inputObject);

        assertNotNull(result);
        assertEquals(inputObject, result);
    }

    // Returns null when input value is not of JsonType.OBJECT
    @Test
    public void test_returns_null_when_input_is_not_object_type() {
        JsonArray inputArray = new JsonArray();
        inputArray.add(new JsonString("test"));

        JsonObjectTypeAdapter converter = new JsonObjectTypeAdapter();
        JsonObject result = converter.fromJson(inputArray);

        assertNull(result);
    }
}