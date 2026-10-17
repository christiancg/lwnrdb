package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.ScriptErrorNames;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class JsonStringifyTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_space_as_number() {
        assertEquals("{\n  \"a\": 1\n}", str("JSON.stringify({a: 1}, null, 2)"));
        assertEquals("[\n    1,\n    2\n]", str("JSON.stringify([1, 2], null, 4)"));
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1}, null, 0)"));
    }

    @Test
    public void test_space_as_string() {
        assertEquals("{\n\t\"a\": 1\n}", str("JSON.stringify({a: 1}, null, '\\t')"));
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1}, null, '')"));
    }

    @Test
    public void test_space_over_limit() {
        assertEquals(10, str("JSON.stringify({a: 1}, null, 40)").split("\n")[1].indexOf('"'));
        assertEquals(10, str("JSON.stringify({a: 1}, null, '..........extra')").split("\n")[1].indexOf('"'));
    }

    @Test
    public void test_space_ignored() {
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1}, null, true)"));
    }

    @Test
    public void test_nested_indentation() {
        assertEquals("{\n  \"a\": {\n    \"b\": [\n      1\n    ]\n  }\n}",
                str("JSON.stringify({a: {b: [1]}}, null, 2)"));
        assertEquals("{\n  \"e\": {},\n  \"f\": []\n}", str("JSON.stringify({e: {}, f: []}, null, 2)"));
    }

    @Test
    public void test_function_replacer() {
        assertEquals("{\"a\":2,\"b\":4}",
                str("JSON.stringify({a: 1, b: 2}, (k, v) => typeof v === 'number' ? v * 2 : v)"));
        assertEquals("{\"a\":\"x\"}", str("JSON.stringify({a: 1}, function (k, v) { return k === 'a' ? 'x' : v })"));
        assertEquals("{}", str("JSON.stringify({a: 1}, (k, v) => k === 'a' ? undefined : v)"));
    }

    @Test
    public void test_array_replacer() {
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1, b: 2}, ['a'])"));
        assertEquals("{\"a\":{\"c\":3}}", str("JSON.stringify({a: {c: 3, d: 4}, b: 2}, ['a', 'c'])"));
        assertEquals("{}", str("JSON.stringify({a: 1}, [])"));
        assertEquals("{\"1\":2}", str("JSON.stringify({1: 2, b: 3}, [1])"));
    }

    @Test
    public void test_replacer_dedup_keeps_first_occurrence_order() {
        assertEquals("{\"c\":3,\"a\":2,\"b\":1}", str("JSON.stringify({b: 1, a: 2, c: 3}, ['c', 'a', 'c', 'b', 'a'])"));
        assertEquals("{\"1\":2,\"x\":{\"1\":4}}", str("JSON.stringify({x: {1: 4}, 1: 2}, [1, '1', 'x', 1])"));
    }

    @Test
    public void test_a_replacer_array_with_many_keys_respects_the_deadline() {
        final var start = System.nanoTime();
        final var distinct = runWithin(5_000, "const keys = Array.from({ length: 300000 }, (_, i) => 'k' + i);"
                + " return JSON.stringify({ k7: 7 }, keys);");
        final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertEquals("{\"k7\":7}", distinct.getValue().asJsonString().getValue(), distinct.getErrorMessage());
        assertTrue(elapsedMillis < 2_000, "300000 replacer keys took " + elapsedMillis + " ms");
    }

    @Test
    public void test_the_replacer_array_build_is_paced_against_the_deadline() {
        final var start = System.nanoTime();
        final var holes = runWithin(100, "const keys = []; keys.length = 2 ** 23; return JSON.stringify({}, keys);");
        final var elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(holes.isError());
        assertEquals(ScriptErrorNames.TIMEOUT, holes.getErrorName(), holes.getErrorMessage());
        assertTrue(elapsedMillis < 2_000, "ran " + elapsedMillis + " ms past a 100 ms deadline");
    }

    private static ScriptResult runWithin(long wallClockMillis, String source) {
        return new SimpleJs().run(source,
                new SimpleHostBindings(new JsonObject(), null, null, new ResourceLimits(-1, wallClockMillis, 200)));
    }

    @Test
    public void test_to_json_hook() {
        assertEquals("{\"x\":1}", str("JSON.stringify({ toJSON() { return {x: 1} } })"));
        assertEquals("\"1970-01-01T00:00:00.000Z\"", str("JSON.stringify(new Date(0))"));
        assertEquals("{\"k\":\"v\"}", str("JSON.stringify({k: { toJSON(key) { return key === 'k' ? 'v' : 'n' } }})"));
    }

    @Test
    public void test_unserialisable_values() {
        assertEquals("{\"b\":1}", str("JSON.stringify({a: undefined, b: 1, c: function () {}})"));
        assertEquals("[null,1,null]", str("JSON.stringify([undefined, 1, function () {}])"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("JSON.stringify(undefined)"));
    }

    @Test
    public void test_cycle_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const o = {}; o.self = o; JSON.stringify(o)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const a = []; a.push(a); JSON.stringify(a)"));
    }

    @Test
    public void test_repeated_object_is_not_a_cycle() {
        assertEquals("{\"a\":{\"n\":1},\"b\":{\"n\":1}}",
                str("const shared = {n: 1}; JSON.stringify({a: shared, b: shared})"));
    }

    @Test
    public void test_non_enumerable_key_skipped() {
        assertEquals("{\"a\":1}", str("const o = {a: 1};"
                + " Object.defineProperty(o, 'b', { value: 2, enumerable: false }); JSON.stringify(o)"));
    }

    @Test
    public void test_round_trip_escapes() {
        assertEquals(JsBoolean.TRUE, Interpreter.run(
                "const x = { s: 'he said \"hi\"\\n\\ttab \\\\slash' };" + " JSON.parse(JSON.stringify(x)).s === x.s"));
        assertTrue(str("JSON.stringify({ s: 'a\"b' }, null, 2)").contains("\\\""));
    }

    @Test
    public void test_space_with_structural_characters_in_strings() {
        assertEquals("{\n  \"a\": \"}{\\\"\"\n}", str("JSON.stringify({a: '}{\"'}, null, 2)"));
    }

    @Test
    public void test_stringify_invokes_getter() {
        assertEquals("{\"x\":1}", str("JSON.stringify({get x() { return 1; }})"));
    }

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
