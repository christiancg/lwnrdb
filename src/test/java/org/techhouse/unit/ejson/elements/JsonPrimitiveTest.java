package org.techhouse.unit.ejson.elements;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.custom_types.JsonDateTime;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ioc.IocContainer;

public class JsonPrimitiveTest {
    @BeforeAll
    public static void registerExtendedTypes() {
        IocContainer.get(EJson.class);
    }

    @Test
    public void test_set_get_primitive_values() {
        JsonString stringPrimitive = new JsonString("test");
        JsonNumber numberPrimitive = new JsonNumber(42);
        JsonBoolean booleanPrimitive = new JsonBoolean(true);

        assertEquals("test", stringPrimitive.getValue());
        assertEquals(42, numberPrimitive.getValue());
        assertEquals(true, booleanPrimitive.getValue());

        stringPrimitive.setValue("updated");
        numberPrimitive.setValue(99);
        booleanPrimitive.setValue(false);

        assertEquals("updated", stringPrimitive.getValue());
        assertEquals(99, numberPrimitive.getValue());
        assertEquals(false, booleanPrimitive.getValue());
    }

    @Test
    public void test_equals_with_null_values() {
        JsonString primitive1 = new JsonString();
        primitive1.setValue(null);

        JsonString primitive2 = new JsonString();
        primitive2.setValue(null);

        JsonString primitive3 = new JsonString();
        primitive3.setValue("not null");

        assertEquals(primitive1, primitive2);
        assertNotEquals(primitive1, primitive3);
        assertNotEquals(null, primitive1);
        assertEquals(primitive1, primitive1);
    }

    @Test
    public void test_hashcode_returns_value_hashcode_for_string() {
        String testValue = "test";
        JsonPrimitive<String> primitive = new JsonString(testValue);

        int expectedHashCode = testValue.hashCode();
        int actualHashCode = primitive.hashCode();

        assertEquals(expectedHashCode, actualHashCode);
    }

    @Test
    public void test_hashcode_returns_31_for_null_value() {
        JsonPrimitive<String> primitive = new JsonString(null);

        int hashCode = primitive.hashCode();

        assertEquals(31, hashCode);
    }

    @Test
    public void test_equal_primitives_with_same_values_returns_true() {
        JsonPrimitive<String> primitive1 = new JsonString("test");
        JsonPrimitive<String> primitive2 = new JsonString("test");

        boolean result = primitive1.equals(primitive2);

        assertTrue(result);
    }

    @Test
    @SuppressWarnings("ConstantValue")
    public void test_equals_with_null_returns_false() {
        JsonPrimitive<String> primitive = new JsonString("test");

        boolean result = primitive.equals(null); // NOPMD - intentional equals(null) contract test

        assertFalse(result);
    }

    @Test
    public void test_json_number_deep_copy_creates_new_instance_with_same_value() {
        JsonNumber original = new JsonNumber(42);

        JsonBaseElement copy = original.deepCopy();

        assertAll(() -> assertInstanceOf(JsonNumber.class, copy), () -> assertNotSame(original, copy), () -> {
            assert copy instanceof JsonNumber;
            assertEquals(original.getValue(), ((JsonNumber) copy).getValue());
        });
    }

    @Test
    public void test_json_boolean_deep_copy() {
        JsonBoolean original = new JsonBoolean(true);
        JsonBaseElement copy = original.deepCopy();
        assertInstanceOf(JsonBoolean.class, copy);
        assertNotSame(original, copy);
        assertEquals(true, ((JsonBoolean) copy).getValue());
    }

    @Test
    public void test_equals_two_numbers_same_value_returns_true() {
        JsonNumber n1 = new JsonNumber(3.14);
        JsonNumber n2 = new JsonNumber(3.14);
        assertEquals(n1, n2);
    }

    @Test
    public void test_equals_two_numbers_different_value_returns_false() {
        JsonNumber n1 = new JsonNumber(1.0);
        JsonNumber n2 = new JsonNumber(2.0);
        assertNotEquals(n1, n2);
    }

    @Test
    public void test_equals_nan_values_returns_true() {
        JsonNumber n1 = new JsonNumber(Double.NaN);
        JsonNumber n2 = new JsonNumber(Double.NaN);
        assertEquals(n1, n2);
    }

    @Test
    public void test_equals_different_primitive_types_returns_false() {
        JsonString s = new JsonString("true");
        JsonBoolean b = new JsonBoolean(true);
        assertNotEquals(s, b);
    }

    @Test
    public void test_hashCode_agrees_with_equals_across_number_boxes() {
        JsonNumber asInteger = new JsonNumber(2);
        JsonNumber asDouble = new JsonNumber(2.0d);

        assertEquals(asInteger, asDouble, "equals compares numbers by double value");
        assertEquals(asInteger.hashCode(), asDouble.hashCode(),
                "the boxed hash split one logical value across two buckets in every hash-based pipeline step,"
                        + " so DISTINCT returned two rows on a scan where the index returned one");
    }

    @Test
    public void test_hashCode_agrees_with_equals_for_negative_zero() {
        JsonNumber positive = new JsonNumber(0.0d);
        JsonNumber negative = new JsonNumber(-0.0d);

        assertEquals(positive, negative, "equals treats the two zeroes as one value");
        assertEquals(positive.hashCode(), negative.hashCode(), "so their hashes must agree too");
    }

    @Test
    public void test_hashCode_agrees_with_equals_for_nan() {
        JsonNumber first = new JsonNumber(Double.NaN);
        JsonNumber second = new JsonNumber(Double.NaN);

        assertEquals(first, second, "equals deliberately makes NaN equal itself");
        assertEquals(first.hashCode(), second.hashCode(), "so their hashes must agree too");
    }

    @Test
    public void test_distinct_numbers_still_hash_apart() {
        assertNotEquals(new JsonNumber(2).hashCode(), new JsonNumber(3).hashCode());
    }

    @Test
    public void test_toString_not_null() {
        JsonString s = new JsonString("hello");
        assertNotNull(s.toString());
    }

    @Test
    public void test_deep_copy_of_a_custom_type_keeps_its_class() {
        JsonDateTime original = new JsonDateTime("#datetime(2024-01-01T10:00:00)");

        JsonBaseElement copy = original.deepCopy();

        assertAll(() -> assertInstanceOf(JsonDateTime.class, copy), () -> assertNotSame(original, copy),
                () -> assertTrue(copy.isJsonCustom()),
                () -> assertEquals(original.getValue(), ((JsonCustom<?>) copy).getValue()),
                () -> {
                    assert copy instanceof JsonDateTime;
                    assertEquals(original.getCustomValue(), ((JsonDateTime) copy).getCustomValue());
                });
    }

    @Test
    public void test_deep_copy_of_every_registered_custom_type_keeps_its_class() {
        List<String> samples = List.of("#datetime(2024-01-01T10:00:00)", "#time(10:15:30)", "#geo(1.5,2.5)",
                "#vector(1.0,2.0,3.0)");

        for (String sample : samples) {
            JsonCustom<?> original = CustomTypeFactory.getCustomTypeInstance(sample);
            JsonBaseElement copy = original.deepCopy();
            assertEquals(original.getClass(), copy.getClass(), sample);
            assertTrue(copy.isJsonCustom(), sample);
            assertEquals(sample, ((JsonCustom<?>) copy).getValue(), sample);
        }
        assertTrue(
                CustomTypeFactory.getCustomTypes().keySet().containsAll(List.of("datetime", "time", "geo", "vector")));
    }

    @Test
    public void test_deep_copy_of_a_plain_string_that_looks_custom_stays_a_string() {
        JsonString original = new JsonString("#datetime(2024-01-01T10:00:00)");

        JsonBaseElement copy = original.deepCopy();

        assertAll(() -> assertEquals(JsonString.class, copy.getClass()), () -> assertFalse(copy.isJsonCustom()),
                () -> assertEquals(original.getValue(), ((JsonString) copy).getValue()));
    }

    @Test
    public void test_json_type_of_a_custom_value_is_custom() {
        JsonDateTime custom = new JsonDateTime("#datetime(2024-01-01T10:00:00)");
        JsonString plain = new JsonString("#datetime(2024-01-01T10:00:00)");

        assertEquals(JsonBaseElement.JsonType.CUSTOM, custom.getJsonType());
        assertEquals(JsonBaseElement.JsonType.STRING, plain.getJsonType());
    }
}
