package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class JsonStringifyTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // A numeric space indents by that many spaces
    @Test
    public void test_space_as_number() {
        assertEquals("{\n  \"a\": 1\n}", str("JSON.stringify({a: 1}, null, 2)"));
        assertEquals("[\n    1,\n    2\n]", str("JSON.stringify([1, 2], null, 4)"));
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1}, null, 0)"));
    }

    // A string space is used verbatim
    @Test
    public void test_space_as_string() {
        assertEquals("{\n\t\"a\": 1\n}", str("JSON.stringify({a: 1}, null, '\\t')"));
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1}, null, '')"));
    }

    // A space over the limit is capped at ten
    @Test
    public void test_space_over_limit() {
        assertEquals(10, str("JSON.stringify({a: 1}, null, 40)").split("\n")[1].indexOf('"'));
        assertEquals(10, str("JSON.stringify({a: 1}, null, '..........extra')").split("\n")[1].indexOf('"'));
    }

    // A non-number, non-string space is ignored
    @Test
    public void test_space_ignored() {
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1}, null, true)"));
    }

    // Nested containers indent cumulatively
    @Test
    public void test_nested_indentation() {
        assertEquals("{\n  \"a\": {\n    \"b\": [\n      1\n    ]\n  }\n}",
                str("JSON.stringify({a: {b: [1]}}, null, 2)"));
        assertEquals("{\n  \"e\": {},\n  \"f\": []\n}", str("JSON.stringify({e: {}, f: []}, null, 2)"));
    }

    // A function replacer maps every value and receives the holder as this
    @Test
    public void test_function_replacer() {
        assertEquals("{\"a\":2,\"b\":4}",
                str("JSON.stringify({a: 1, b: 2}, (k, v) => typeof v === 'number' ? v * 2 : v)"));
        assertEquals("{\"a\":\"x\"}", str("JSON.stringify({a: 1}, function (k, v) { return k === 'a' ? 'x' : v })"));
        assertEquals("{}", str("JSON.stringify({a: 1}, (k, v) => k === 'a' ? undefined : v)"));
    }

    // An array replacer is a key allowlist
    @Test
    public void test_array_replacer() {
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1, b: 2}, ['a'])"));
        assertEquals("{\"a\":{\"c\":3}}", str("JSON.stringify({a: {c: 3, d: 4}, b: 2}, ['a', 'c'])"));
        assertEquals("{}", str("JSON.stringify({a: 1}, [])"));
        assertEquals("{\"1\":2}", str("JSON.stringify({1: 2, b: 3}, [1])"));
    }

    // toJSON is consulted before serialising
    @Test
    public void test_to_json_hook() {
        assertEquals("{\"x\":1}", str("JSON.stringify({ toJSON() { return {x: 1} } })"));
        assertEquals("\"1970-01-01T00:00:00.000Z\"", str("JSON.stringify(new Date(0))"));
        assertEquals("{\"k\":\"v\"}", str("JSON.stringify({k: { toJSON(key) { return key === 'k' ? 'v' : 'n' } }})"));
    }

    // undefined, functions and symbols are omitted in objects and become null in arrays
    @Test
    public void test_unserialisable_values() {
        assertEquals("{\"b\":1}", str("JSON.stringify({a: undefined, b: 1, c: function () {}})"));
        assertEquals("[null,1,null]", str("JSON.stringify([undefined, 1, function () {}])"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("JSON.stringify(undefined)"));
    }

    // A cycle on the current path is rejected
    @Test
    public void test_cycle_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = {}; o.self = o; JSON.stringify(o)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const a = []; a.push(a); JSON.stringify(a)"));
    }

    // The same object appearing twice on different paths is not a cycle
    @Test
    public void test_repeated_object_is_not_a_cycle() {
        assertEquals("{\"a\":{\"n\":1},\"b\":{\"n\":1}}",
                str("const shared = {n: 1}; JSON.stringify({a: shared, b: shared})"));
    }

    // Non-enumerable own keys are skipped
    @Test
    public void test_non_enumerable_key_skipped() {
        assertEquals("{\"a\":1}", str("const o = {a: 1};"
                + " Object.defineProperty(o, 'b', { value: 2, enumerable: false }); JSON.stringify(o)"));
    }

    // Escaped characters survive a stringify/parse round trip
    @Test
    public void test_round_trip_escapes() {
        assertEquals(JsBoolean.TRUE, Interpreter.run(
                "const x = { s: 'he said \"hi\"\\n\\ttab \\\\slash' };" + " JSON.parse(JSON.stringify(x)).s === x.s"));
        assertTrue(str("JSON.stringify({ s: 'a\"b' }, null, 2)").contains("\\\""));
    }

    // Indentation is not confused by braces or quotes inside string values
    @Test
    public void test_space_with_structural_characters_in_strings() {
        assertEquals("{\n  \"a\": \"}{\\\"\"\n}", str("JSON.stringify({a: '}{\"'}, null, 2)"));
    }

    // stringify reads an enumerable accessor through its getter
    @Test
    public void test_stringify_invokes_getter() {
        assertEquals("{\"x\":1}", str("JSON.stringify({get x() { return 1; }})"));
    }

    // a non-enumerable accessor is skipped like a non-enumerable data property
    @Test
    public void test_stringify_skips_non_enumerable_accessor() {
        final var source = """
                const o = {a: 1};
                Object.defineProperty(o, 'x', { get() { return 2; } });
                JSON.stringify(o)
                """;
        assertEquals("{\"a\":1}", str(source));
    }
}
