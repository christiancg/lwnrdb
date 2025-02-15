package org.techhouse.unit.ejson;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.MalformedJsonException;
import org.techhouse.ejson.internal.JsonReader;
import org.techhouse.ejson.internal.JsonWriter;
import org.techhouse.test.TestUtils;

import static org.junit.jupiter.api.Assertions.*;

public class EJsonTest {
    // Serialize and deserialize primitive types (boolean, number, string) correctly
    @Test
    public void test_primitive_types_serialization_deserialization() {
        EJson eJson = new EJson();

        TestPrimitives obj = new TestPrimitives();
        obj.boolVal = true;
        obj.numVal = 42;
        obj.strVal = "test";

        String json = eJson.toJson(obj);
        TestPrimitives result = eJson.fromJson(json, TestPrimitives.class);

        assertEquals(obj.boolVal, result.boolVal);
        assertEquals(obj.numVal, result.numVal);
        assertEquals(obj.strVal, result.strVal);
    }

    private static class TestPrimitives {
        public boolean boolVal;
        public int numVal;
        public String strVal;
    }

    // Handle null values in serialization and deserialization
    @Test
    public void test_null_values_handling() {
        EJson eJson = new EJson();

        TestNullable obj = new TestNullable();
        obj.nullableString = null;
        obj.nullableNumber = null;

        String json = eJson.toJson(obj);
        TestNullable result = eJson.fromJson(json, TestNullable.class);

        assertNull(result.nullableString);
        assertNull(result.nullableNumber);
    }

    private static class TestNullable {
        public String nullableString;
        public Integer nullableNumber;
    }

    // Serialize basic Java objects to JSON strings using toJson()
    @Test
    public void test_serialize_basic_java_objects() {
        EJson eJson = new EJson();

        String stringResult = eJson.toJson("test");
        assertEquals("\"test\"", stringResult);

        Integer intResult = 42;
        assertEquals("42", eJson.toJson(intResult));

        Boolean boolResult = true;
        assertEquals("true", eJson.toJson(boolResult));

        Double doubleResult = 3.14;
        assertEquals("3.14", eJson.toJson(doubleResult));
    }

    // Constructor successfully initializes JsonReader and JsonWriter instances
    @Test
    public void test_constructor_initializes_reader_writer() {
        EJson eJson = new EJson();
        assertNotNull(eJson);
        String jsonString = "{\"test\": 123}";
        JsonObject result = eJson.fromJson(jsonString, JsonObject.class);
        assertNotNull(result);
        String output = eJson.toJson(123);
        assertNotNull(output);
    }

    // Parse valid JSON string into corresponding Java object of specified class
    @Test
    public void parse_valid_json_to_object() {
        EJson ejson = new EJson();
        String json = "{\"name\":\"test\",\"value\":123}";

        TestClass result = ejson.fromJson(json, TestClass.class);

        assertNotNull(result);
        assertEquals("test", result.getName());
        assertEquals(123, result.getValue());
    }

    @Getter
    @Setter
    private static class TestClass {
        private String name;
        private int value;
    }

    // Handle empty JSON string input
    @Test
    public void handle_empty_json_string() {
        EJson eJson = new EJson();
        String emptyJson = "";

        assertThrows(MalformedJsonException.class, () -> eJson.fromJson(emptyJson, TestClass.class));
    }

    @Getter
    @Setter
    private static class SimpleTestClass {
        private String name;
        private int value;
    }

    // Convert simple object to JSON string using its class type
    @Test
    public void test_convert_simple_object_to_json() {
        EJson eJson = new EJson();

        SimpleTestClass testObj = new SimpleTestClass();
        testObj.setName("test");
        testObj.setValue(123);

        String result = eJson.toJson(testObj);

        assertEquals("{\"name\":\"test\",\"value\":123}", result);
    }

    // Convert simple object to JSON string using its class type
    @Test
    public void test_convert_simple_object_to_json2() {
        EJson eJson = new EJson();
        String testString = "test";

        String result = eJson.toJson(testString);

        assertEquals("\"test\"", result);
    }

    // Handle null input object
    @Test
    public void test_handle_null_input() {
        EJson eJson = new EJson();

        assertThrows(NullPointerException.class, () -> eJson.toJson(null));
    }

    // Handle null input object
    @Test
    public void reader_and_writer_are_not_null() throws NoSuchFieldException, IllegalAccessException {
        EJson eJson = new EJson();
        final var reader = TestUtils.getPrivateField(eJson, "reader", JsonReader.class);
        assertNotNull(reader);
        final var writer = TestUtils.getPrivateField(eJson, "writer", JsonWriter.class);
        assertNotNull(writer);
    }

    // Handle null JsonBaseElement input
    @Test
    public void test_handle_null_json_element() {
        JsonBaseElement jsonElement = null;
        Class<String> targetClass = String.class;

        EJson eJson = new EJson();

        assertThrows(NullPointerException.class, () -> eJson.fromJson(jsonElement, targetClass));
    }
}