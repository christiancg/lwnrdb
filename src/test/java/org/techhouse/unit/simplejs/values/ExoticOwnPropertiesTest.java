package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsAsyncGenerator;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGenerator;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsPromise;
import org.techhouse.simplejs.values.JsRegExp;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public class ExoticOwnPropertiesTest {
    private static final JsObject.PropertyFlags HIDDEN = new JsObject.PropertyFlags(false, false, false);

    private static Map<String, JsValue> adoptingTypes() {
        final var buffer = new JsArrayBuffer(8);
        final Map<String, JsValue> types = new LinkedHashMap<>();
        types.put("JsObject", new JsObject());
        types.put("JsArray", new JsArray());
        types.put("JsFunction", new JsFunction("f", List.of(), null, false, false, false, false, Environment.global()));
        types.put("JsNativeFunction", new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance()));
        types.put("JsClass", new JsClass("C", null, Environment.global()));
        types.put("JsPromise", new JsPromise(new EventLoop()));
        types.put("JsTypedArray", new JsTypedArray(JsTypedArray.Kind.INT8, buffer, 0, 8));
        types.put("JsArrayBuffer", buffer);
        types.put("JsDataView", new JsDataView(buffer, 0, 8));
        types.put("JsDate", new JsDate(0));
        types.put("JsMap", new JsMap());
        types.put("JsSet", new JsSet());
        types.put("JsRegExp", new JsRegExp("a", "", Pattern.compile("a")));
        types.put("JsArguments", new JsArguments(List.of(), null, null));
        types.put("JsGlobalObject", new JsGlobalObject(Environment.global()));
        types.put("JsGenerator", new JsGenerator(null));
        types.put("JsAsyncGenerator", new JsAsyncGenerator(null));
        return types;
    }

    // Every adopting type answers a real table, so defineProperty is never silently ignored
    @Test
    public void test_define_property_is_not_silently_ignored() {
        for (final var entry : adoptingTypes().entrySet()) {
            final var table = entry.getValue().ownProperties();
            assertNotNull(table, entry.getKey());
            table.defineValue("marker", new JsNumber(1));
            assertTrue(table.has("marker"), entry.getKey());
            assertEquals(1, ((JsNumber) table.get("marker")).getValue(), entry.getKey());
        }
    }

    // The flags stored on a key are the flags reported back, per type
    @Test
    public void test_get_own_property_descriptor_reports_real_flags() {
        for (final var entry : adoptingTypes().entrySet()) {
            final var table = entry.getValue().ownProperties();
            table.defineValue("marker", new JsNumber(1));
            table.setFlags("marker", HIDDEN);
            assertEquals(HIDDEN, table.getFlags("marker"), entry.getKey());
        }
    }

    // delete removes a configurable own property and rejects a non-configurable one
    @Test
    public void test_delete_removes_configurable_own_property() {
        for (final var entry : adoptingTypes().entrySet()) {
            final var table = entry.getValue().ownProperties();
            table.defineValue("marker", new JsNumber(1));
            table.setFlags("marker", HIDDEN);
            assertFalse(table.delete("marker"), entry.getKey());
            table.setFlags("marker", JsObject.PropertyFlags.DEFAULT);
            assertTrue(table.delete("marker"), entry.getKey());
            assertFalse(table.has("marker"), entry.getKey());
        }
    }

    // Primitives keep a null table, which is what distinguishes them from an object
    @Test
    public void test_primitives_have_no_table() {
        assertNull(new JsNumber(1).ownProperties());
        assertNull(new JsString("a").ownProperties());
        assertNull(JsBoolean.TRUE.ownProperties());
        assertNull(new JsBigInt(BigInteger.ONE).ownProperties());
        assertNull(JsUndefined.getInstance().ownProperties());
        assertNull(JsNull.getInstance().ownProperties());
        assertNull(new JsSymbol("x").ownProperties());
    }

    // An array's index/length exotica win over the table; the table only holds named keys
    @Test
    public void test_array_exotic_behaviour_takes_precedence_over_table() {
        final var array = new JsArray(List.of(new JsNumber(1), new JsNumber(2)));
        array.setProperty("named", new JsNumber(9));
        assertEquals(2, array.length());
        assertEquals(1, ((JsNumber) array.get(0)).getValue());
        assertFalse(array.ownProperties().has("0"));
        assertTrue(array.ownProperties().has("named"));
    }

    // A typed array's canonical numeric index reads through the buffer, not the table
    @Test
    public void test_typed_array_exotic_behaviour_takes_precedence_over_table() {
        final var typed = new JsTypedArray(JsTypedArray.Kind.INT8, new JsArrayBuffer(2), 0, 2);
        typed.setElement(0, new JsNumber(7));
        assertEquals(7, ((JsNumber) typed.getElement(0)).getValue());
        assertFalse(typed.ownProperties().has("0"));
    }

    // globalThis keeps its bindings in the Environment, never duplicated into the table
    @Test
    public void test_global_object_falls_through_to_the_environment() {
        final var env = Environment.global();
        final var global = new JsGlobalObject(env);
        env.setGlobal("x", new JsNumber(4));
        assertTrue(env.isDeclared("x"));
        assertFalse(global.ownProperties().has("x"));
    }

    // A class and its static owner share one table rather than the substrate seeing two
    @Test
    public void test_class_shares_its_static_owner_table() {
        final var cls = new JsClass("C", null, Environment.global());
        assertSame(cls.getStaticOwner().ownProperties(), cls.ownProperties());
    }

    // Intrinsics are per-Interpreter, so a monkey-patch in one run must not leak into the next
    @Test
    public void test_tables_are_not_shared_across_runs() {
        Interpreter.run("Object.defineProperty(Array.prototype, 'zz', { value: 1 });");
        assertEquals("undefined", ((JsString) Interpreter.run("typeof [].zz")).getValue());
    }
}
