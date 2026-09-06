package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class CustomTypeProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Every custom type coerces to the EJson wire text the storage layer already parses
    @Test
    public void test_string_coercion_of_every_type() {
        assertEquals("#geo(1.0,2.0)", str("String(new Geo(1, 2))"));
        assertEquals("#vector(1.0,2.0)", str("String(new Vector([1, 2]))"));
        assertEquals("#datetime(2024-01-02T03:04:05)", str("String(new DbDateTime(2024, 1, 2, 3, 4, 5))"));
        assertEquals("#time(03:04:05)", str("String(new DbTime(3, 4, 5))"));
    }

    // JSON.stringify goes through each type's toJSON, so it emits the wire text as a JSON string
    @Test
    public void test_json_stringify_of_every_type() {
        assertEquals("{\"at\":\"#geo(1.0,2.0)\"}", str("JSON.stringify({ at: new Geo(1, 2) })"));
        assertEquals("{\"v\":\"#vector(1.0,2.0)\"}", str("JSON.stringify({ v: new Vector([1, 2]) })"));
        assertEquals("\"#datetime(2024-01-02T03:04:05)\"", str("JSON.stringify(new DbDateTime(2024, 1, 2, 3, 4, 5))"));
        assertEquals("\"#time(03:04:05)\"", str("JSON.stringify(new DbTime(3, 4, 5))"));
    }

    // structuredClone keeps the type rather than falling through to the generic object path
    @Test
    public void test_structured_clone_preserves_the_type() {
        assertEquals("[object Geo]", str("Object.prototype.toString.call(structuredClone(new Geo(1, 2)))"));
        assertEquals("[object Vector]", str("Object.prototype.toString.call(structuredClone(new Vector([1])))"));
        assertEquals("[object DbDateTime]", str("Object.prototype.toString.call(structuredClone(new DbDateTime()))"));
        assertEquals("[object DbTime]", str("Object.prototype.toString.call(structuredClone(new DbTime()))"));
        assertEquals(1, num("structuredClone(new Geo(1, 2)).lat"));
    }

    // A member write lands as an ordinary own property, leaving the wrapped value untouched
    @Test
    public void test_member_writes_are_ordinary_properties() {
        assertEquals(7, num("const g = new Geo(1, 2); g.note = 7; g.note"));
        assertEquals(1, num("const g = new Geo(1, 2); g.note = 7; g.lat"));
    }

    // The four globals are non-enumerable, so they never show up in Object.keys(globalThis)
    @Test
    public void test_globals_are_non_enumerable() {
        assertTrue(bool("!Object.keys(globalThis).includes('Geo')"));
        assertTrue(bool("Object.getOwnPropertyNames(globalThis).includes('Geo')"));
        assertTrue(bool("Object.getOwnPropertyNames(globalThis).includes('Vector')"));
        assertTrue(bool("Object.getOwnPropertyNames(globalThis).includes('DbDateTime')"));
        assertTrue(bool("Object.getOwnPropertyNames(globalThis).includes('DbTime')"));
    }
}
