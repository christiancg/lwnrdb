package org.techhouse.unit.utils;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.exceptions.WrongFormatCustomTypeException;
import org.techhouse.utils.ReflectionUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.utils.ReflectionUtils.getClassFromSimpleName;

public class ReflectionUtilsTest {
    // TypeToken correctly extracts Class<T> from direct subclass with simple generic type
    @Test
    public void test_type_token_extracts_class_from_direct_subclass() {
        ReflectionUtils.TypeToken<String> token = new ReflectionUtils.TypeToken<>() {};

        Class<String> result = token.getTypeParameter();

        assertEquals(String.class, result);
    }

    // TypeToken throws IllegalStateException for raw TypeToken without type argument
    @Test
    @SuppressWarnings("rawtypes")
    public void test_type_token_throws_for_raw_type() {
        ReflectionUtils.TypeToken<?> rawToken = new ReflectionUtils.TypeToken() {};

        IllegalStateException exception = assertThrows(
            IllegalStateException.class,
            rawToken::getTypeParameter
        );

        assertTrue(exception.getMessage().contains("TypeToken must be created with a type argument"));
    }

    // Returns String.class when input is "String"
    @Test
    public void test_returns_string_class_for_string_input() {
        Class<?> result = getClassFromSimpleName("String");

        assertEquals(String.class, result);
    }

    // Returns Number.class when input is "Number"
    @Test
    public void test_returns_number_class() {
        Class<?> result = getClassFromSimpleName("Number");
        assertEquals(Number.class, result);
    }

    // Returns Boolean.class when input is "Boolean"
    @Test
    public void test_returns_boolean_class() {
        Class<?> result = getClassFromSimpleName("Boolean");
        assertEquals(Boolean.class, result);
    }

    public static class MyCustomType extends JsonCustom<String> {
        @Override
        public String getCustomTypeName() {
            return "MyCustomType";
        }
        @Override
        protected String parse() throws WrongFormatCustomTypeException {
            return "";
        }
        @Override
        public Integer compare(String another) {
            return 0;
        }
    }

    // Returns registered custom type class when input matches registered type name
    @Test
    public void test_returns_registered_custom_type_class() {
        CustomTypeFactory.registerCustomType(MyCustomType.class);
        Class<?> result = getClassFromSimpleName("MyCustomType");
        assertEquals(MyCustomType.class, result);
    }

}