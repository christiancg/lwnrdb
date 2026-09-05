package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;

public class EJsonInteropAccessorTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    private static JsObject withGetter() {
        final var object = new JsObject();
        object.defineAccessor("n", new JsNativeFunction("get n", (_, _) -> new JsNumber(7)), null);
        return object;
    }

    // Without an interpreter to call the getter with, the data-property-only behaviour is kept
    @Test
    public void test_reads_data_value_when_ops_is_null() {
        final var converted = EJsonInterop.toHostEjson(withGetter()).asJsonObject();
        assertFalse(converted.has("n") && converted.get("n").isJsonNumber());
    }

    @Test
    public void test_getter_valued_property_crosses_the_boundary() {
        final var result = run("return { get total() { return 6 * 7; } }");
        assertFalse(result.isError());
        assertEquals(42, result.getValue().asJsonObject().get("total").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_data_properties_are_unaffected() {
        final var result = run("return { a: 1, get b() { return 2; } }");
        final var object = result.getValue().asJsonObject();
        assertEquals(1, object.get("a").asJsonNumber().getValue().intValue());
        assertEquals(2, object.get("b").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_throwing_getter_is_propagated() {
        final var result = run("return { get bad() { throw new RangeError('nope'); } }");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // Enumerability still filters first, so a non-enumerable accessor stays out
    @Test
    public void test_non_enumerable_accessor_is_skipped() {
        final var result = run("""
                const o = {};
                Object.defineProperty(o, 'hidden', { get() { return 1; }, enumerable: false });
                o.shown = 2;
                return o;
                """);
        final var object = result.getValue().asJsonObject();
        assertFalse(object.has("hidden"));
        assertEquals(2, object.get("shown").asJsonNumber().getValue().intValue());
    }

    @Test
    public void test_nested_getter_is_invoked() {
        final var result = run("return { inner: { get n() { return 3; } } }");
        assertEquals(3, result.getValue().asJsonObject().get("inner").asJsonObject().get("n").asJsonNumber().getValue()
                .intValue());
    }

    @Test
    public void test_getter_inside_an_array_element_is_invoked() {
        final var result = run("return [{ get n() { return 4; } }]");
        assertEquals(4,
                result.getValue().asJsonArray().get(0).asJsonObject().get("n").asJsonNumber().getValue().intValue());
    }

    // The cycle guard still fires when a getter hands back the object being converted
    @Test
    public void test_cyclic_getter_is_rejected() {
        final var result = run("""
                const o = {};
                Object.defineProperty(o, 'self', { get() { return o; }, enumerable: true });
                return o;
                """);
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_getter_returning_undefined_is_dropped_like_a_data_undefined() {
        final var object = new JsObject();
        object.set("gone", JsUndefined.getInstance());
        assertFalse(EJsonInterop.toHostEjson(object).asJsonObject().has("gone"));
    }
}
