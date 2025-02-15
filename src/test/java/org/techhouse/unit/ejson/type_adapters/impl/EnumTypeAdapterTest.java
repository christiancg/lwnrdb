package org.techhouse.unit.ejson.type_adapters.impl;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.type_adapters.impl.EnumTypeAdapter;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class EnumTypeAdapterTest {
    enum TestEnum {
        VALUE1, VALUE2, VALUE3, VALUE_ONE
    }

    // Converting valid enum value to JSON string with quotes
    @Test
    public void test_valid_enum_to_json_string() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(TestEnum.VALUE1);

        assertEquals("\"VALUE1\"", result);
    }

    // Handling null enum value in toJson
    @Test
    public void test_null_enum_to_json() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(null);

        assertEquals("\"null\"", result);
    }

    // Constructor accepts valid enum class and initializes clazz field correctly
    @Test
    public void test_constructor_accepts_valid_enum_class() {
        EnumTypeAdapter<TimeUnit> adapter = new EnumTypeAdapter<>(TimeUnit.class);

        JsonBaseElement jsonElement = new JsonString("SECONDS");
        TimeUnit result = adapter.fromJson(jsonElement);

        assertEquals(TimeUnit.SECONDS, result);
    }

    // Convert enum value to JSON string with proper double quotes
    @Test
    public void test_enum_value_converted_to_json_string() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(TestEnum.VALUE1);

        assertEquals("\"VALUE1\"", result);
    }

    // Handle null enum value input
    @Test
    public void test_null_enum_value_handling() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        String result = adapter.toJson(null);

        assertEquals("\"null\"", result);
    }

    // Successfully converts valid enum string value to corresponding enum constant
    @Test
    public void test_valid_enum_string_conversion() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        JsonString jsonString = new JsonString("VALUE_ONE");

        TestEnum result = adapter.fromJson(jsonString);

        assertEquals(TestEnum.VALUE_ONE, result);
    }

    // Returns null when input JsonBaseElement is not of STRING type
    @Test
    public void test_non_string_input_returns_null() {
        EnumTypeAdapter<TestEnum> adapter = new EnumTypeAdapter<>(TestEnum.class);

        JsonNumber jsonNumber = new JsonNumber(123);

        TestEnum result = adapter.fromJson(jsonNumber);

        assertNull(result);
    }
}