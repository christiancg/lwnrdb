package org.techhouse.unit.ejson.type_adapters.impl;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.impl.ReflectionTypeAdapter;
import static org.junit.jupiter.api.Assertions.*;

public class ReflectionTypeAdapterTest {
    @BeforeEach
    public void setUp() {
        new EJson();
    }

    // Serialize object with primitive fields to JSON string
    @Test
    public void test_serialize_primitive_fields() {
        new EJson();
        @Data
        class TestClass {
            private int intField = 42;
            private boolean boolField = true;
            private double doubleField = 3.14;
        }

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);

        TestClass testObj = new TestClass();
        String json = adapter.toJson(testObj);

        assertEquals("{\"intField\":42,\"boolField\":true,\"doubleField\":3.14}", json);
    }

    // Handle null field values during serialization and deserialization
    @Test
    public void test_handle_null_fields() {
        @Data
        class TestClass {
            private String nullField = null;
            private Integer nullInteger = null;
        }

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);

        TestClass testObj = new TestClass();
        String json = adapter.toJson(testObj);

        assertEquals("{\"nullField\":null,\"nullInteger\":null}", json);

        JsonObject jsonObj = new JsonObject();
        jsonObj.add("nullField", JsonNull.INSTANCE);
        jsonObj.add("nullInteger", JsonNull.INSTANCE);

        TestClass deserializedObj = adapter.fromJson(jsonObj);
        assertNotNull(deserializedObj);
        assertNull(deserializedObj.nullField);
        assertNull(deserializedObj.nullInteger);
    }

    // Constructor successfully initializes with valid Class<T> parameter
    @Test
    public void test_constructor_initializes_with_valid_class() {
        ReflectionTypeAdapter<String> adapter = new ReflectionTypeAdapter<>(String.class);

        assertNotNull(adapter);
    }

    // Constructor handles null class parameter
    @Test
    public void test_constructor_handles_null_class() {
        assertThrows(NullPointerException.class, () -> new ReflectionTypeAdapter<>(null));
    }

    // Converts simple object with primitive fields to valid JSON string
    @Test
    public void test_converts_simple_object_to_json() {
        @Data
        class TestClass {
            private int intField = 42;
            private String stringField = "test";
            private boolean boolField = true;
        }

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);
        TestClass testObj = new TestClass();

        String result = adapter.toJson(testObj);

        assertEquals("{\"intField\":42,\"stringField\":\"test\",\"boolField\":true}", result);
    }

    // Handles null object value fields by converting them to "null" string
    @Test
    public void test_converts_null_fields_to_null_string() {
        @Data
        class TestClass {
            private String nullField = null;
            private Integer nullInteger = null;
        }

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);
        TestClass testObj = new TestClass();

        String result = adapter.toJson(testObj);

        assertEquals("{\"nullField\":null,\"nullInteger\":null}", result);
    }

    // Successfully converts JsonObject to target class instance with matching field names
    @Test
    public void test_converts_json_object_to_target_class() {
        @Getter
        @Setter
        class TestClass {
            private String stringField;
            private Integer intField;
        }

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("stringField", "test");
        jsonObject.addProperty("intField", 123);

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);
        TestClass result = adapter.fromJson(jsonObject);

        assertNotNull(result);
        assertEquals("test", result.getStringField());
        assertEquals(Integer.valueOf(123), result.getIntField());
    }

    // Returns null when input is not a JsonObject type
    @Test
    public void test_returns_null_for_non_object_input() {
        @Data
        class TestClass {
            private String field;
        }

        JsonArray jsonArray = new JsonArray();
        jsonArray.add("test");

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);
        TestClass result = adapter.fromJson(jsonArray);

        assertNull(result);
    }
}