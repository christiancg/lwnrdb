package org.techhouse.unit.ejson.type_adapters.impl;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.impl.ReflectionTypeAdapter;

public class ReflectionTypeAdapterTest {
    @BeforeEach

    @Test
    public void test_serialize_primitive_fields() {
        new EJson();
        @SuppressWarnings("unused")
        class TestClass {
            private final int intField = 42;
            private final boolean boolField = true;
            private final double doubleField = 3.14;
        }

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);

        TestClass testObj = new TestClass();
        String json = adapter.toJson(testObj);

        assertEquals("{\"intField\":42,\"boolField\":true,\"doubleField\":3.14}", json);
    }

    @Test
    public void test_handle_null_fields() {
        @SuppressWarnings("unused")
        class TestClass {
            private final String nullField = null;
            private final Integer nullInteger = null;
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

        // The fields are final, so this is also what proves the deserializer can still assign them:
        // asserting only the nulls above would pass just as well if the assignment silently failed.
        final var populated = new JsonObject();
        populated.addProperty("nullField", "value");
        populated.addProperty("nullInteger", 5);
        final var assigned = adapter.fromJson(populated);
        assertEquals("value", assigned.nullField);
        assertEquals(Integer.valueOf(5), assigned.nullInteger);
    }

    @Test
    public void test_constructor_initializes_with_valid_class() {
        ReflectionTypeAdapter<String> adapter = new ReflectionTypeAdapter<>(String.class);

        assertNotNull(adapter);
    }

    @Test
    public void test_constructor_handles_null_class() {
        assertThrows(NullPointerException.class, () -> new ReflectionTypeAdapter<>(null));
    }

    @Test
    public void test_converts_simple_object_to_json() {
        @SuppressWarnings("unused")
        class TestClass {
            private final int intField = 42;
            private final String stringField = "test";
            private final boolean boolField = true;
        }

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);
        TestClass testObj = new TestClass();

        String result = adapter.toJson(testObj);

        assertEquals("{\"intField\":42,\"stringField\":\"test\",\"boolField\":true}", result);
    }

    @Test
    public void test_converts_null_fields_to_null_string() {
        @SuppressWarnings("unused")
        class TestClass {
            private final String nullField = null;
            private final Integer nullInteger = null;
        }

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);
        TestClass testObj = new TestClass();

        String result = adapter.toJson(testObj);

        assertEquals("{\"nullField\":null,\"nullInteger\":null}", result);
    }

    @Test
    public void test_converts_json_object_to_target_class() {
        @SuppressWarnings("unused")
        class TestClass {
            private String stringField;
            private Integer intField;

            public String getStringField() {
                return stringField;
            }

            public Integer getIntField() {
                return intField;
            }
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

    @Test
    public void test_returns_null_for_non_object_input() {
        @SuppressWarnings("unused")
        class TestClass {
            private String field;
        }

        JsonArray jsonArray = new JsonArray();
        jsonArray.add("test");

        ReflectionTypeAdapter<TestClass> adapter = new ReflectionTypeAdapter<>(TestClass.class);
        TestClass result = adapter.fromJson(jsonArray);

        assertNull(result);
    }

    // Emitting statics put every `public static final` on the wire beside the real fields, and on the
    // way back the deserializer would try to assign them from the document.
    @Test
    public void test_static_fields_are_not_serialized() {
        final var json = new EJson().toJson(new WithConstants());

        assertFalse(json.contains("DECISION_ACCEPT"), "a static constant leaked onto the wire: " + json);
        assertFalse(json.contains("COUNTER"), "a static field leaked onto the wire: " + json);
        assertTrue(json.contains("\"decision\":\"accept\""), "the instance field is missing: " + json);
    }

    @SuppressWarnings("unused")
    public static class WithConstants {
        public static final String DECISION_ACCEPT = "accept";
        private static final int COUNTER = 7;
        private final String decision = "accept";
    }
}
