package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
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
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse('{bad}')"));
    }

    @Test
    public void parseCoercesTextThroughToString() {
        assertEquals(42, num("JSON.parse(42)"));
        assertEquals(1, num("JSON.parse({ toString: () => '[1]' })[0]"));
    }

    @Test
    public void parseRejectsIllegalJsonText() {
        for (final var text : List.of("'01'", "'{\"a\":1,}'", "'[1,]'", "'\\'a\\''", "'undefined'", "'1 2'", "'.5'",
                "'+1'", "'NaN'", "'[1] junk'")) {
            assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse(" + text + ")"), text);
        }
    }

    @Test
    public void parsePreservesNegativeZero() {
        assertEquals(Double.NEGATIVE_INFINITY, num("1 / JSON.parse('-0')"));
        assertEquals(Double.POSITIVE_INFINITY, num("1 / JSON.parse('0')"));
    }

    @Test
    public void parseDoesNotHonourProtoKey() {
        final var parsed = "JSON.parse('{\"__proto__\": {\"x\": 1}}')";
        assertEquals("true", str("String(Object.prototype.hasOwnProperty.call(" + parsed + ", '__proto__'))"));
        assertEquals(1, num(parsed + ".__proto__.x"));
        assertEquals("undefined", str("typeof " + parsed + ".x"));
    }

    @Test
    public void reviverWalksViaOwnPropertyKeys() {
        assertEquals("a,b",
                str("let seen = [];"
                        + " JSON.parse('{\"a\":1,\"b\":2}', function (k, v) { if (k) seen.push(k); return v; });"
                        + " seen.join(',')"));
        assertEquals("undefined", str("typeof JSON.parse('{\"a\":1}', (k, v) => k === 'a' ? undefined : v).a"));
    }

    @Test
    public void stringifyHandlesProxyAndBoxedPrimitives() {
        assertEquals("{\"a\":1}", str("JSON.stringify(new Proxy({a: 1}, {}))"));
        assertEquals("[1,2]", str("JSON.stringify(new Proxy([1, 2], {}))"));
        assertEquals("1", str("JSON.stringify(new Number(1))"));
        assertEquals("\"x\"", str("JSON.stringify(new String('x'))"));
        assertEquals("true", str("JSON.stringify(new Boolean(true))"));
    }

    // the EJson extended types the DB persists survive a stringify/parse round trip as strings
    @Test
    public void ejsonCustomTypesRoundTripAsStrings() {
        final var source = "let doc = { g: '#geo(1.5,2.5)', v: '#vector(1,2,3)', d: '2020-01-02T03:04:05.000Z' };"
                + " let back = JSON.parse(JSON.stringify(doc)); ";
        assertEquals("#geo(1.5,2.5)", str(source + "back.g"));
        assertEquals("#vector(1,2,3)", str(source + "back.v"));
        assertEquals("2020-01-02T03:04:05.000Z", str(source + "back.d"));
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

    @Test
    public void parseReadsTheThreeKeywordLiterals() {
        assertEquals("true", str("String(JSON.parse('true'))"));
        assertEquals("false", str("String(JSON.parse('false'))"));
        assertEquals("object", str("typeof JSON.parse('null')"));
        assertEquals("true", str("String(JSON.parse(' \\t\\r\\n true \\n '))"));
    }

    @Test
    public void parseRejectsTruncatedKeywordLiterals() {
        for (final var text : List.of("'tru'", "'fals'", "'nul'", "'t'", "'n'", "'f'")) {
            assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse(" + text + ")"), text);
        }
    }

    @Test
    public void parseReadsEmptyContainers() {
        assertEquals(0, num("Object.keys(JSON.parse('{}')).length"));
        assertEquals(0, num("JSON.parse('[]').length"));
        assertEquals(0, num("JSON.parse('{ }').length || 0"));
        assertEquals(0, num("JSON.parse('[ ]').length"));
    }

    @Test
    public void parseRejectsEmptyAndBlankText() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse('')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse('   ')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse('[')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse('{')"));
    }

    @Test
    public void parseReadsEveryStringEscape() {
        assertEquals(8, num("JSON.parse('\"\\\\b\"').charCodeAt(0)"));
        assertEquals(12, num("JSON.parse('\"\\\\f\"').charCodeAt(0)"));
        assertEquals(10, num("JSON.parse('\"\\\\n\"').charCodeAt(0)"));
        assertEquals(13, num("JSON.parse('\"\\\\r\"').charCodeAt(0)"));
        assertEquals(9, num("JSON.parse('\"\\\\t\"').charCodeAt(0)"));
        assertEquals("/", str("JSON.parse('\"\\\\/\"')"));
        assertEquals("\\", str("JSON.parse('\"\\\\\\\\\"')"));
        assertEquals("\"", str("JSON.parse('\"\\\\\\\"\"')"));
        assertEquals("A", str("JSON.parse('\"\\\\u0041\"')"));
    }

    // a lone surrogate survives parsing: JSON text is scanned as code units, not code points
    @Test
    public void parseKeepsALoneSurrogate() {
        assertEquals(0xD834, num("JSON.parse('\"\\\\ud834\"').charCodeAt(0)"));
        assertEquals(1, num("JSON.parse('\"\\\\ud834\"').length"));
    }

    @Test
    public void parseRejectsBadStringContent() {
        for (final var text : List.of("'\"abc'", "'\"\\\\'", "'\"\\\\q\"'", "'\"\\\\u00\"'", "'\"\\\\uZZZZ\"'",
                "'\"\\\\u12g4\"'", "'\"a\\u0001b\"'")) {
            assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse(" + text + ")"), text);
        }
    }

    @Test
    public void parseReadsFractionsAndExponents() {
        assertEquals(1.5, num("JSON.parse('1.5')"));
        assertEquals(-1.5, num("JSON.parse('-1.5')"));
        assertEquals(1500, num("JSON.parse('1.5e3')"));
        assertEquals(1500, num("JSON.parse('1.5E+3')"));
        assertEquals(0.0015, num("JSON.parse('1.5e-3')"));
        assertEquals(-0.5, num("JSON.parse('-0.5')"));
    }

    @Test
    public void parseRejectsMalformedNumbers() {
        for (final var text : List.of("'1.'", "'1e'", "'1e+'", "'-'", "'1.2.3'", "'--1'")) {
            assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse(" + text + ")"), text);
        }
    }

    @Test
    public void parseRejectsMalformedStructure() {
        for (final var text : List.of("'{\"a\" 1}'", "'{\"a\":1 \"b\":2}'", "'[1 2]'", "'{a:1}'", "'{1:2}'", "'[1,2'",
                "'{\"a\":1'")) {
            assertThrows(SyntaxErrorException.class, () -> Interpreter.run("JSON.parse(" + text + ")"), text);
        }
    }

    @Test
    public void parseReadsDeeplyNestedText() {
        assertEquals(1, num("JSON.parse('[[[[[[[[[[1]]]]]]]]]]')[0][0][0][0][0][0][0][0][0][0]"));
        assertEquals(1, num("JSON.parse('{\"a\":{\"a\":{\"a\":{\"a\":1}}}}').a.a.a.a"));
    }

    @Test
    public void stringifyWithoutArgumentsIsUndefined() {
        assertEquals("undefined", str("typeof JSON.stringify()"));
    }

    @Test
    public void stringifyReadsBoxedPropertyListEntriesAndSkipsOthers() {
        assertEquals("{\"a\":1}", str("JSON.stringify({a: 1, b: 2}, [new String('a')])"));
        assertEquals("{\"1\":2}", str("JSON.stringify({1: 2, b: 3}, [new Number(1)])"));
        assertEquals("{}", str("JSON.stringify({a: 1}, [true, null, {}])"));
    }

    @Test
    public void stringifyUnwrapsABoxedSpaceArgument() {
        assertEquals("{\n  \"a\": 1\n}", str("JSON.stringify({a: 1}, null, new Number(2))"));
        assertEquals("{\n--\"a\": 1\n}", str("JSON.stringify({a: 1}, null, new String('--'))"));
    }

    @Test
    public void stringifyFiltersProxyKeysThroughThePropertyList() {
        assertEquals("{\"a\":1}", str("JSON.stringify(new Proxy({a: 1, b: 2}, {}), ['a'])"));
    }
}
