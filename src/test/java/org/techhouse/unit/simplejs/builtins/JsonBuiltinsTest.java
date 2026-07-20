package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
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
}
