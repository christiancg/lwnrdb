package org.techhouse.unit.ejson.custom_types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.BadImplementationCustomTypeException;
import org.techhouse.ejson.exceptions.NonRegisteredCustomTypeException;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;

public class CustomTypeFactoryTest {
    @BeforeEach
    public void setUp() {
        CustomTypeFactory.getCustomTypes().clear();
    }

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

        @Override
        public Set<String> customOperatorNames() {
            return Set.of();
        }

        @Override
        public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> customRankingOperatorNames() {
            return Set.of();
        }

        @Override
        public double applyCustomRankingOperator(String operatorName, Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    public void test_register_invalid_custom_type_without_default_constructor() {
        assertThrows(BadImplementationCustomTypeException.class,
                () -> CustomTypeFactory.registerCustomType(InvalidCustomType.class));
    }

    private static class InvalidCustomType extends JsonCustom<Locale> {
        InvalidCustomType(Locale value) {
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

        @Override
        public Set<String> customOperatorNames() {
            return Set.of();
        }

        @Override
        public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> customRankingOperatorNames() {
            return Set.of();
        }

        @Override
        public double applyCustomRankingOperator(String operatorName, Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    public void test_empty_map_when_no_types_registered() {
        Map<String, Class<? extends JsonCustom<?>>> customTypes = CustomTypeFactory.getCustomTypes();

        assertTrue(customTypes.isEmpty());
    }

    @Test
    public void test_converts_json_string_to_custom_type() {
        CustomTypeFactory.registerCustomType(ValidCustomType.class);

        ValidCustomType input = new ValidCustomType("#valid(123)");

        JsonCustom<?> result = CustomTypeFactory.getCustomTypeInstance(input);

        assertNotNull(result);
        assertInstanceOf(ValidCustomType.class, result);
        assertEquals("#valid(123)", result.getValue());
    }

    @Test
    public void test_handles_null_value_json_string() {
        CustomTypeFactory.registerCustomType(ValidCustomType.class);
        assertThrows(WrongFormatCustomTypeException.class, () -> new ValidCustomType(""));
    }

    public static class TestCustomType extends JsonCustom<Locale> {
        public TestCustomType() {
            super();
        }

        public TestCustomType(String value) {
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

        @Override
        public Set<String> customOperatorNames() {
            return Set.of();
        }

        @Override
        public boolean applyCustomOperator(String operatorName, Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> customRankingOperatorNames() {
            return Set.of();
        }

        @Override
        public double applyCustomRankingOperator(String operatorName, Map<String, JsonBaseElement> args) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    public void test_creates_custom_type_instance_from_valid_input() {
        CustomTypeFactory.registerCustomType(TestCustomType.class);

        String validInput = "#test(value)";

        JsonCustom<?> result = CustomTypeFactory.getCustomTypeInstance(validInput);

        assertNotNull(result);
        assertInstanceOf(TestCustomType.class, result);
        assertEquals("#test(value)", result.getValue());
    }

    @Test
    public void test_throws_exception_for_unregistered_type() {
        CustomTypeFactory.getCustomTypes().clear();

        String invalidInput = "#unknown(value)";

        NonRegisteredCustomTypeException exception = assertThrows(NonRegisteredCustomTypeException.class,
                () -> CustomTypeFactory.getCustomTypeInstance(invalidInput));

        assertEquals("The custom type unknown has not been registered", exception.getMessage());
    }

    @Test
    public void test_get_instance_with_invalid_format_throws_bad_implementation() {
        new org.techhouse.ejson.EJson(); // registers custom types including time
        assertThrows(BadImplementationCustomTypeException.class,
                () -> CustomTypeFactory.getCustomTypeInstance("#time(not_a_valid_time)"));
    }
}
