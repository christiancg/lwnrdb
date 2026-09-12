package org.techhouse.unit.ejson.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.ejson.internal.ReflectionUtils.cast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.elements.JsonSyntaxToken;
import org.techhouse.ejson.internal.ReflectionUtils;

public class ReflectionUtilsTest {
    @Test
    public void test_get_set_field_values() throws IllegalAccessException {
        class TestClass {
            private final String testField = "initial"; // NOPMD - reflection/serialization test fixture
        }

        TestClass instance = new TestClass();
        Field field = ReflectionUtils.getFields(TestClass.class)[0];

        String initialValue = (String) ReflectionUtils.getFieldValue(field, instance);
        assertEquals("initial", initialValue);

        ReflectionUtils.setFieldValue(field, instance, "updated");
        String updatedValue = (String) ReflectionUtils.getFieldValue(field, instance);
        assertEquals("updated", updatedValue);
    }

    @Test
    public void test_access_private_members() {
        final class PrivateClass {
            private String privateField = "initial"; // NOPMD - reflection/serialization test fixture
            public String getPrivateField() {
                return privateField;
            }
            public void setPrivateField(String privateField) {
                this.privateField = privateField;
            }
        }
        Constructor<?>[] constructors = ReflectionUtils.getConstructors(PrivateClass.class);
        Field[] fields = ReflectionUtils.getFields(PrivateClass.class);

        assertEquals(1, constructors.length);
        assertTrue(constructors[0].canAccess(null));

        assertEquals(1, fields.length);
        assertTrue(fields[0].canAccess(new PrivateClass()));
        assertEquals("privateField", fields[0].getName());
    }

    static class TestClass {
        public String publicField;
    }

    @Test
    public void test_get_public_field_value() throws IllegalAccessException, NoSuchFieldException {
        TestClass testObj = new TestClass();
        testObj.publicField = "test value";
        Field field = TestClass.class.getField("publicField");
        field.setAccessible(true);
        Object result = ReflectionUtils.getFieldValue(field, testObj);
        assertEquals("test value", result);
    }

    @Test
    public void test_get_field_value_null_instance() throws NoSuchFieldException {
        Field field = TestClass.class.getField("publicField");

        assertThrows(NullPointerException.class, () -> ReflectionUtils.getFieldValue(field, null));
    }

    @Test
    public void test_set_public_field_value_with_matching_type() throws IllegalAccessException, NoSuchFieldException {
        TestClass testObj = new TestClass();
        Field field = TestClass.class.getField("publicField");
        String newValue = "test value";
        field.setAccessible(true);
        ReflectionUtils.setFieldValue(field, testObj, newValue);
        assertEquals(newValue, testObj.publicField);
    }

    @Test
    public void test_set_field_value_with_null_instance() throws NoSuchFieldException {
        Field field = TestClass.class.getField("publicField");
        String newValue = "test value";
        assertThrows(NullPointerException.class, () -> ReflectionUtils.setFieldValue(field, null, newValue));
    }

    @Test
    public void test_returns_cached_fields_when_class_exists() {
        Field[] fields1 = ReflectionUtils.getFields(JsonString.class);

        Field[] fields2 = ReflectionUtils.getFields(JsonString.class);

        assertNotNull(fields2);
        assertArrayEquals(fields1, fields2);
    }

    @Test
    public void test_null_class_parameter() {
        assertThrows(NullPointerException.class, () -> ReflectionUtils.getFields(null));
    }

    @Test
    public void test_returns_cached_constructors_when_class_exists() {
        Constructor<?>[] firstCall = ReflectionUtils.getConstructors(JsonString.class);

        Constructor<?>[] secondCall = ReflectionUtils.getConstructors(JsonString.class);

        assertNotNull(secondCall);
        assertArrayEquals(firstCall, secondCall);
    }

    @Test
    public void test_throws_exception_when_null_class() {
        assertThrows(NullPointerException.class, () -> ReflectionUtils.getConstructors(null));
    }

    @Test
    public void test_create_instance_with_public_no_args_constructor() throws Exception {
        class TestClass {
            TestClass() {
            }
        }

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("someField", "someValue");

        TestClass result = ReflectionUtils.createInstance(TestClass.class, jsonObject);

        assertNotNull(result);
        assertInstanceOf(TestClass.class, result);
    }

    @Test
    public void test_create_instance_with_no_public_constructors() {
        final class TestClass {
            private TestClass() {
            }
        }

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("someField", "someValue");

        assertDoesNotThrow(() -> {
            ReflectionUtils.createInstance(TestClass.class, jsonObject);
        });
    }

    @Test
    public void test_cast_invalid_enum_value() {
        JsonString invalidEnumValue = new JsonString("INVALID_VALUE");

        assertThrows(InvocationTargetException.class, () -> cast(TestEnum.class, invalidEnumValue, null));
    }

    public enum TestEnum {
        VALUE1, VALUE2
    }

    @Test
    public void test_cast_null_json_type_returns_null() throws Exception {
        assertNull(cast(String.class, JsonNull.INSTANCE, null));
    }

    @Test
    public void test_cast_number_to_integer() throws Exception {
        JsonNumber num = new JsonNumber(42.7);
        assertEquals(Integer.valueOf(42), cast(Integer.class, num, null));
    }

    @Test
    public void test_cast_number_to_double() throws Exception {
        JsonNumber num = new JsonNumber(3.14);
        assertEquals(Double.valueOf(3.14), cast(Double.class, num, null));
    }

    @Test
    public void test_cast_number_to_float() throws Exception {
        JsonNumber num = new JsonNumber(1.5);
        assertEquals(Float.valueOf(1.5f), cast(Float.class, num, null));
    }

    @Test
    public void test_cast_number_to_long() throws Exception {
        JsonNumber num = new JsonNumber(100.9);
        assertEquals(Long.valueOf(100L), cast(Long.class, num, null));
    }

    @Test
    public void test_cast_number_to_primitive_targets() throws Exception {
        JsonNumber large = new JsonNumber("10000000000");
        assertEquals(Long.valueOf(10000000000L), cast(long.class, large, null));
        assertEquals(Double.valueOf(1e10), cast(double.class, large, null));

        JsonNumber num = new JsonNumber("100.9");
        assertEquals(Integer.valueOf(100), cast(int.class, num, null));
        assertEquals(Float.valueOf(100.9f), cast(float.class, num, null));
        assertEquals(Short.valueOf((short) 100), cast(short.class, num, null));
        assertEquals(Byte.valueOf((byte) 100), cast(byte.class, num, null));
    }

    @Test
    public void test_cast_non_number_to_primitive_is_unchanged() throws Exception {
        assertEquals(Boolean.TRUE, cast(boolean.class, new org.techhouse.ejson.elements.JsonBoolean(true), null));
    }

    @Test
    public void test_cast_syntax_token_returns_null() throws Exception {
        assertNull(cast(String.class, JsonSyntaxToken.COMMA, null));
    }
}
