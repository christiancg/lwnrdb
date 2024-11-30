package org.techhouse.unit.ejson;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;

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
}