package org.techhouse.unit.ejson.internal;

import lombok.Getter;
import lombok.Setter;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.internal.ReflectionUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.*;
import static org.techhouse.ejson.internal.ReflectionUtils.cast;

public class ReflectionUtilsTest {
    // Get and set field values for accessible class fields
    @Test
    public void test_get_set_field_values() throws IllegalAccessException {
        @Getter
        @Setter
        class TestClass {
            private String testField = "initial";
        }

        TestClass instance = new TestClass();
        Field field = ReflectionUtils.getFields(TestClass.class)[0];

        String initialValue = (String) ReflectionUtils.getFieldValue(field, instance);
        assertEquals("initial", initialValue);

        ReflectionUtils.setFieldValue(field, instance, "updated");
        String updatedValue = (String) ReflectionUtils.getFieldValue(field, instance);
        assertEquals("updated", updatedValue);
    }

    // Access private fields and constructors by setting accessible flag
    @Test
    public void test_access_private_members() {
        @Getter
        @Setter
        class PrivateClass {
            private final String privateField;
            private PrivateClass(String value) {
                this.privateField = value;
            }
        }
        Constructor<?>[] constructors = ReflectionUtils.getConstructors(PrivateClass.class);
        Field[] fields = ReflectionUtils.getFields(PrivateClass.class);

        assertEquals(1, constructors.length);
        assertTrue(constructors[0].canAccess(null));
    
        assertEquals(1, fields.length);
        assertTrue(fields[0].canAccess(new PrivateClass("initial")));
        assertEquals("privateField", fields[0].getName());
    }

    static class TestClass {
        public String publicField;
    }

    // Get value of public field from instance
    @Test
    public void test_get_public_field_value() throws IllegalAccessException, NoSuchFieldException {
        TestClass testObj = new TestClass();
        testObj.publicField = "test value";
        Field field = TestClass.class.getField("publicField");
        field.setAccessible(true);
        Object result = ReflectionUtils.getFieldValue(field, testObj);
        assertEquals("test value", result);
    }

    // Get value from null instance
    @Test
    public void test_get_field_value_null_instance() throws NoSuchFieldException {
        Field field = TestClass.class.getField("publicField");

        assertThrows(NullPointerException.class, () -> ReflectionUtils.getFieldValue(field, null));
    }

    // Set public field value for instance with matching field type
    @Test
    public void test_set_public_field_value_with_matching_type() throws IllegalAccessException, NoSuchFieldException {
        TestClass testObj = new TestClass();
        Field field = TestClass.class.getField("publicField");
        String newValue = "test value";
        field.setAccessible(true);
        ReflectionUtils.setFieldValue(field, testObj, newValue);
        assertEquals(newValue, testObj.publicField);
    }

    // Set field value with null instance parameter
    @Test
    public void test_set_field_value_with_null_instance() throws NoSuchFieldException {
        Field field = TestClass.class.getField("publicField");
        String newValue = "test value";
        assertThrows(NullPointerException.class, () -> ReflectionUtils.setFieldValue(field, null, newValue));
    }

    // Returns cached fields when class exists in classSpecifications map
    @Test
    public void test_returns_cached_fields_when_class_exists() {
        // First call to populate cache
        Field[] fields1 = ReflectionUtils.getFields(JsonString.class);

        // Second call should return cached fields
        Field[] fields2 = ReflectionUtils.getFields(JsonString.class);

        assertNotNull(fields2);
        assertArrayEquals(fields1, fields2);
    }

    // Pass null as tClass parameter
    @Test
    public void test_null_class_parameter() {
        assertThrows(NullPointerException.class, () -> ReflectionUtils.getFields(null));
    }

    // Returns cached constructors when class exists in classSpecifications map
    @Test
    public void test_returns_cached_constructors_when_class_exists() {
        // First call to populate cache
        Constructor<?>[] firstCall = ReflectionUtils.getConstructors(JsonString.class);

        // Second call should return cached constructors
        Constructor<?>[] secondCall = ReflectionUtils.getConstructors(JsonString.class);

        assertNotNull(secondCall);
        assertArrayEquals(firstCall, secondCall);
    }

    // Passing null as tClass parameter
    @Test
    public void test_throws_exception_when_null_class() {
        assertThrows(NullPointerException.class, () -> ReflectionUtils.getConstructors(null));
    }

    // Create instance using public no-args constructor
    @Test
    public void test_create_instance_with_public_no_args_constructor() throws Exception {
        class TestClass {
            public TestClass() {}
        }

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("someField", "someValue");

        TestClass result = ReflectionUtils.createInstance(TestClass.class, jsonObject);

        assertNotNull(result);
        assertInstanceOf(TestClass.class, result);
    }

    // Handle class with no public constructors
    @Test
    public void test_create_instance_with_no_public_constructors() {
        class TestClass {
            private TestClass() {}
        }

        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("someField", "someValue");

        assertDoesNotThrow(() -> {
            ReflectionUtils.createInstance(TestClass.class, jsonObject);
        });
    }

    // Handle invalid enum values
    @Test
    public void test_cast_invalid_enum_value() {
        JsonString invalidEnumValue = new JsonString("INVALID_VALUE");

        assertThrows(InvocationTargetException.class, () -> cast(TestEnum.class, invalidEnumValue, null));
    }

    public enum TestEnum {
        VALUE1,
        VALUE2
    }
}