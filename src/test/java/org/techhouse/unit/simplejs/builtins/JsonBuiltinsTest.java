package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class JsonBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // parse reads scalars, arrays and nested objects
    @Test
    public void test_parse() {
        assertEquals(2, num("JSON.parse('[1,2,3]')[1]"));
        assertEquals(5, num("JSON.parse('{\"a\":{\"b\":5}}').a.b"));
        assertEquals("hi", str("JSON.parse('\"hi\"')"));
    }

    // stringify produces a string that parses back to the same structure
    @Test
    public void test_stringify_roundtrip() {
        assertEquals(3, num("JSON.parse(JSON.stringify({a: 1, b: [2, 3]})).b[1]"));
        assertEquals(2, num("JSON.parse(JSON.stringify([1, 2, 3])).length - 1"));
        assertEquals("string", str("typeof JSON.stringify({})"));
    }

    // stringify drops undefined and returns undefined at the top level
    @Test
    public void test_stringify_undefined() {
        assertEquals("undefined", str("typeof JSON.stringify(undefined)"));
    }

    // a circular structure cannot be serialized
    @Test
    public void test_circular_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("let o = {}; o.self = o; JSON.stringify(o)"));
    }

    // a BigInt cannot be serialized
    @Test
    public void test_bigint_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("JSON.stringify(5n)"));
    }

    // parsing invalid JSON throws a SyntaxError
    @Test
    public void test_parse_invalid_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse(42)"));
    }

    // stringify accepts a null argument
    @Test
    public void test_stringify_null() {
        assertTrue(str("JSON.stringify(null)").contains("null"));
    }

    // the reviver rewrites every value bottom-up
    @Test
    public void test_parse_reviver_doubles_numbers() {
        assertEquals(2, num("JSON.parse('{\"a\":1}', (k, v) => typeof v === 'number' ? v * 2 : v).a"));
        assertEquals(2, num("JSON.parse('1', (k, v) => v * 2)"));
    }

    // a nested object is revived depth-first
    @Test
    public void test_parse_reviver_nested() {
        final var source = "JSON.parse('{\"a\":{\"b\":2}}', (k, v) => typeof v === 'number' ? v + 1 : v).a.b";
        assertEquals(3, num(source));
    }

    // an undefined reviver result deletes the key
    @Test
    public void test_parse_reviver_undefined_deletes_key() {
        final var source = "Object.keys(JSON.parse('{\"a\":1,\"b\":2}', (k, v) => k === 'a' ? undefined : v)).join(',')";
        assertEquals("b", str(source));
    }

    // array elements are revived by index
    @Test
    public void test_parse_reviver_array_elements() {
        assertEquals("2,4,6", str("JSON.parse('[1,2,3]', (k, v) => typeof v === 'number' ? v * 2 : v).join(',')"));
    }

    // the reviver sees the holder as this and the root key last
    @Test
    public void test_parse_reviver_root_key_and_holder() {
        final var source = """
                let keys = [];
                let rootHolder = null;
                JSON.parse('{"a":1}', function (k, v) { keys.push(k); if (k === '') rootHolder = this; return v; });
                keys.join('|') + '#' + (typeof rootHolder === 'object')
                """;
        assertEquals("a|#true", str(source));
    }

    // reviver keys follow own-key order
    @Test
    public void test_parse_reviver_key_order() {
        final var source = """
                let keys = [];
                JSON.parse('{"b":1,"a":2}', (k, v) => { keys.push(k); return v; });
                keys.join(',')
                """;
        assertEquals("b,a,", str(source));
    }

    // a throwing reviver propagates out of parse
    @Test
    public void test_parse_reviver_throws() {
        assertThrows(JsThrowException.class,
                () -> Interpreter.run("JSON.parse('{\"a\":1}', () => { throw new TypeError('x'); })"));
    }

    // a non-callable second argument is ignored
    @Test
    public void test_parse_non_callable_reviver_ignored() {
        assertEquals(1, num("JSON.parse('{\"a\":1}', null).a"));
    }
}
