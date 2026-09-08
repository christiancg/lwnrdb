package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.custom_types.JsonGeo;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.simplejs.values.EJsonInterop;

public class EJsonInteropEstimatedBytesTest {

    @Test
    public void test_estimates_string_by_length() {
        final var shorter = EJsonInterop.estimatedBytes(new JsonString("ab"));
        final var longer = EJsonInterop.estimatedBytes(new JsonString("abcdefghij"));
        assertTrue(longer > shorter, "a longer string must estimate larger");
        assertEquals(8 * 2, longer - shorter);
    }

    @Test
    public void test_estimates_nested_object_as_sum_of_members() {
        final var inner = new JsonObject();
        inner.add("value", new JsonNumber(1));
        final var outer = new JsonObject();
        outer.add("inner", inner);
        final var expected = EJsonInterop.estimatedBytes(new JsonObject()) + "inner".length() * 2
                + EJsonInterop.estimatedBytes(inner);
        assertEquals(expected, EJsonInterop.estimatedBytes(outer));
    }

    @Test
    public void test_estimates_array_as_sum_of_elements() {
        final var array = new JsonArray();
        array.add(new JsonNumber(1));
        array.add(new JsonBoolean(true));
        array.add(JsonNull.INSTANCE);
        final var expected = EJsonInterop.estimatedBytes(new JsonArray())
                + EJsonInterop.estimatedBytes(new JsonNumber(1)) + EJsonInterop.estimatedBytes(new JsonBoolean(true))
                + EJsonInterop.estimatedBytes(JsonNull.INSTANCE);
        assertEquals(expected, EJsonInterop.estimatedBytes(array));
    }

    @Test
    public void test_null_element_estimates_zero() {
        assertEquals(0, EJsonInterop.estimatedBytes(null));
    }

    // A JsonCustom answers STRING from getJsonType, so it has to be recognised by its Java type or it
    // would be estimated as an unknown node
    @Test
    public void test_handles_custom_types() {
        final var geo = new JsonGeo("#geo(1.5,2.5)");
        assertEquals(EJsonInterop.estimatedBytes(new JsonString(geo.getValue())), EJsonInterop.estimatedBytes(geo));
    }
}
