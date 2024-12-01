package org.techhouse.unit.ejson.custom_types;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.BadImplementationCustomTypeException;
import org.techhouse.ejson.exceptions.NonRegisteredCustomTypeException;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CustomTypeFactoryTest {
    @BeforeEach
    public void setUp() {
        CustomTypeFactory.getCustomTypes().clear();
    }

    // Register a valid custom type class with proper implementation
    @Test
    public void test_register_valid_custom_type() {
        CustomTypeFactory.registerCustomType(ValidCustomType.class);

        Map<String, Class<? extends JsonCustom<?>>> registeredTypes = CustomTypeFactory.getCustomTypes();

        assertEquals(1, registeredTypes.size());
        assertTrue(registeredTypes.containsKey("valid"));
        assertEquals(ValidCustomType.class, registeredTypes.get("valid"));
    }

    public static class ValidCustomType extends JsonCustom<Locale> {
        public ValidCustomType() {
            super();
        }

        public ValidCustomType(Locale value) {
            super(value);
        }

        public ValidCustomType(String value) {
            super(value);
        }

        @Override
        public String getCustomTypeName() {
            return "valid";
        }

        @Override
        protected Locale parse() {
            return Locale.getDefault();
        }

        @Override
        public Integer compare(Locale another) {
            return this.customValue.hashCode() == another.hashCode() ? 0 : -1;
        }
    }

    // Register custom type class without default constructor
    @Test
    public void test_register_invalid_custom_type_without_default_constructor() {
        assertThrows(BadImplementationCustomTypeException.class, () -> CustomTypeFactory.registerCustomType(InvalidCustomType.class));
    }

    private static class InvalidCustomType extends JsonCustom<Locale> {
        public InvalidCustomType(Locale value) {
            super(value);
        }

        @Override
        public String getCustomTypeName() {
            return "invalid";
        }

        @Override
        protected Locale parse() {
            return Locale.getDefault();
        }

        @Override
        public Integer compare(Locale another) {
            return this.customValue.hashCode() == another.hashCode() ? 0 : -1;
        }
    }

    // Return empty map when no custom types are registered
    @Test
    public void test_empty_map_when_no_types_registered() {
        Map<String, Class<? extends JsonCustom<?>>> customTypes = CustomTypeFactory.getCustomTypes();

        assertTrue(customTypes.isEmpty());
    }

    // Successfully converts JsonString to JsonCustom by delegating to String-based method
    @Test
    public void test_converts_json_string_to_custom_type() {
        CustomTypeFactory.registerCustomType(ValidCustomType.class);

        ValidCustomType input = new ValidCustomType("#valid(123)");

        JsonCustom<?> result = CustomTypeFactory.getCustomTypeInstance(input);

        assertNotNull(result);
        assertInstanceOf(ValidCustomType.class, result);
        assertEquals("#valid(123)", result.getValue());
    }

    // Handles JsonString with null value
    @Test
    public void test_handles_null_value_json_string() {
        CustomTypeFactory.registerCustomType(ValidCustomType.class);
        assertThrows(WrongFormatCustomTypeException.class, () -> new ValidCustomType(""));
    }

    // Create test custom type class
    public static class TestCustomType extends JsonCustom<Locale> {
        public TestCustomType() {
            super();
        }
        public TestCustomType(String value) {
            super(value);
        }
        public TestCustomType(Locale value) {
            super(value);
        }
        @Override
        public String getCustomTypeName() {
            return "test";
        }
        @Override
        protected Locale parse() {
            return Locale.getDefault();
        }
        @Override
        public Integer compare(Locale another) {
            return 0;
        }
    }

    // Successfully creates instance of registered custom type from valid string input
    @Test
    public void test_creates_custom_type_instance_from_valid_input() {
        // Register the test custom type
        CustomTypeFactory.registerCustomType(TestCustomType.class);

        // Test valid input string
        String validInput = "#test(value)";

        // Execute
        JsonCustom<?> result = CustomTypeFactory.getCustomTypeInstance(validInput);

        // Verify
        assertNotNull(result);
        assertInstanceOf(TestCustomType.class, result);
        assertEquals("#test(value)", result.getValue());
    }

    // Throws NonRegisteredCustomTypeException when type name not found in _customTypes map
    @Test
    public void test_throws_exception_for_unregistered_type() {
        // Clear any registered types
        CustomTypeFactory.getCustomTypes().clear();

        // Test input with unregistered type
        String invalidInput = "#unknown(value)";

        // Execute and verify exception
        NonRegisteredCustomTypeException exception = assertThrows(
                NonRegisteredCustomTypeException.class,
                () -> CustomTypeFactory.getCustomTypeInstance(invalidInput)
        );

        assertEquals("The custom type unknown has not been registered", exception.getMessage());
    }
}