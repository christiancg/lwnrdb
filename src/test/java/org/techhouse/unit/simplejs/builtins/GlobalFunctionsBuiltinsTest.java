package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class GlobalFunctionsBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // joins an accumulator array read after the event loop has drained
    private static String joinDrained() {
        final var array = (JsArray) Interpreter.run("let order = [];\nsetTimeout(() => order.push('timer'), 0);\nqueueMicrotask(() => order.push('micro'));\norder\n");
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JsCoercion.toStr(array.get(i)));
        }
        return sb.toString();
    }

    // encodeURIComponent escapes reserved characters that encodeURI preserves
    @Test
    public void test_encode_uri_variants() {
        assertEquals("a%20b", str("encodeURI('a b')"));
        assertEquals("a%2Fb", str("encodeURIComponent('a/b')"));
        assertEquals("a/b", str("encodeURI('a/b')"));
    }

    // decodeURIComponent round-trips multi-byte UTF-8
    @Test
    public void test_decode_uri_round_trip() {
        assertEquals("✓", str("decodeURIComponent('%E2%9C%93')"));
        assertEquals("é", str("decodeURIComponent('%C3%A9')"));
        assertEquals("a b/c", str("decodeURIComponent(encodeURIComponent('a b/c'))"));
        assertEquals("a b", str("decodeURI('a%20b')"));
        assertEquals("a%2Fb", str("decodeURI('a%2Fb')"));
    }

    // a malformed percent sequence throws a URIError
    @Test
    public void test_decode_uri_malformed_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    decodeURIComponent('%E0%A4');
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("URIError", str(source));
    }

    // escape and unescape round-trip a non-ASCII string
    @Test
    public void test_escape_unescape() {
        assertEquals("%u2713", str("escape('✓')"));
        assertEquals("✓", str("unescape('%u2713')"));
        assertEquals("a b", str("unescape(escape('a b'))"));
    }

    // encode round-trips a four-byte (astral) code point
    @Test
    public void test_encode_astral_round_trip() {
        assertEquals("%F0%9F%98%80", str("encodeURIComponent('😀')"));
        assertEquals("😀", str("decodeURIComponent('%F0%9F%98%80')"));
    }

    // encodeURI rejects a lone surrogate with a URIError
    @Test
    public void test_encode_lone_surrogate_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    encodeURI(String.fromCharCode(0xD800));
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("URIError", str(source));
    }

    // a truncated multi-byte sequence throws a URIError
    @Test
    public void test_decode_truncated_multibyte_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    decodeURIComponent('%F0%9F');
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("URIError", str(source));
    }

    // unescape decodes both %XX and %uXXXX escapes and passes literals through
    @Test
    public void test_unescape_forms() {
        assertEquals("A", str("unescape('%41')"));
        assertEquals("plain", str("unescape('plain')"));
        assertEquals("%zz", str("unescape('%zz')"));
    }

    // escape encodes a low byte as %XX and passes unescaped characters through
    @Test
    public void test_escape_low_byte() {
        assertEquals("%20", str("escape(' ')"));
        assertEquals("aA1@*_+-./", str("escape('aA1@*_+-./')"));
    }

    // structuredClone copies dates, maps, sets, typed arrays and buffers
    @Test
    public void test_structured_clone_builtin_types() {
        assertEquals(1000, num("structuredClone(new Date(1000)).getTime()"));
        assertEquals(1, num("structuredClone(new Map([['a', 1]])).get('a')"));
        assertTrue(bool("structuredClone(new Set([1, 2])).has(2)"));
        assertEquals(7, num("structuredClone(new Int8Array([5, 7]))[1]"));
        assertEquals(4, num("structuredClone(new ArrayBuffer(4)).byteLength"));
    }

    // structuredClone preserves primitive leaf values
    @Test
    public void test_structured_clone_primitives() {
        final var source = """
                const copy = structuredClone({ n: 1, s: 'x', b: true, z: null, big: 9n });
                (copy.n === 1) && (copy.s === 'x') && (copy.b === true) && (copy.z === null) && (copy.big === 9n)
                """;
        assertTrue(bool(source));
    }

    // structuredClone deep-copies nested structures
    @Test
    public void test_structured_clone_deep() {
        final var source = """
                const original = { a: 1, nested: { b: [2, 3] } };
                const copy = structuredClone(original);
                copy.nested.b.push(4);
                original.nested.b.length * 100 + copy.nested.b.length
                """;
        assertEquals(203, num(source));
    }

    // structuredClone preserves cycles
    @Test
    public void test_structured_clone_cycle() {
        final var source = """
                const a = { name: 'a' };
                a.self = a;
                const copy = structuredClone(a);
                copy.self === copy
                """;
        assertTrue(bool(source));
    }

    // cloning a function throws a TypeError
    @Test
    public void test_structured_clone_function_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    structuredClone(() => 1);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // queueMicrotask runs before a zero-delay timer
    @Test
    public void test_queue_microtask_ordering() {
        assertEquals("micro,timer", joinDrained());
    }

    // queueMicrotask with a non-function argument throws a TypeError
    @Test
    public void test_queue_microtask_non_function_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    queueMicrotask(5);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }
}
