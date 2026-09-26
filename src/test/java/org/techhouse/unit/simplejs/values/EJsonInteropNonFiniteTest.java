package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;

public class EJsonInteropNonFiniteTest {
    @Test
    public void test_host_conversion_refuses_positive_infinity() {
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(new JsNumber(Double.POSITIVE_INFINITY)));
    }

    @Test
    public void test_host_conversion_refuses_negative_infinity() {
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(new JsNumber(Double.NEGATIVE_INFINITY)));
    }

    @Test
    public void test_host_conversion_refuses_nan() {
        assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(new JsNumber(Double.NaN)));
    }

    @Test
    public void test_host_conversion_names_the_member_path() {
        final var inner = new JsObject();
        inner.set("b", new JsNumber(Double.POSITIVE_INFINITY));
        final var outer = new JsObject();
        outer.set("a", inner);

        final var error = assertThrows(TypeErrorException.class, () -> EJsonInterop.toHostEjson(outer));

        assertTrue(error.getMessage().contains("a.b"), error.getMessage());
        assertTrue(error.getMessage().contains("Infinity"), error.getMessage());
    }

    @Test
    public void test_host_conversion_accepts_every_finite_double() {
        assertEquals(Double.MAX_VALUE,
                EJsonInterop.toHostEjson(new JsNumber(Double.MAX_VALUE)).asJsonNumber().getValue().doubleValue());
        assertEquals(Double.MIN_VALUE,
                EJsonInterop.toHostEjson(new JsNumber(Double.MIN_VALUE)).asJsonNumber().getValue().doubleValue());
        assertEquals(0.0, EJsonInterop.toHostEjson(new JsNumber(0.0)).asJsonNumber().getValue().doubleValue());
        assertEquals(-0.0, EJsonInterop.toHostEjson(new JsNumber(-0.0)).asJsonNumber().getValue().doubleValue());
    }

    @Test
    public void test_stringify_conversion_still_accepts_non_finite() {
        assertTrue(Double.isInfinite(
                EJsonInterop.toEjson(new JsNumber(Double.POSITIVE_INFINITY)).asJsonNumber().getValue().doubleValue()));
        assertTrue(
                Double.isNaN(EJsonInterop.toEjson(new JsNumber(Double.NaN)).asJsonNumber().getValue().doubleValue()));
    }
}
