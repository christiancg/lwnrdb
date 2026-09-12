package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class RegexBuiltinsTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_constructor_from_string() {
        assertTrue(bool("new RegExp('a.c').test('axc')"));
        assertTrue(bool("RegExp('a', 'i').test('A')"));
    }

    @Test
    public void test_constructor_clone() {
        assertEquals("i", str("new RegExp(/a/i).flags"));
        assertEquals("g", str("new RegExp(/a/i, 'g').flags"));
        assertEquals("a", str("new RegExp(/a/i).source"));
    }

    @Test
    public void test_test_method() {
        assertTrue(bool("/\\d+/.test('abc123')"));
        assertFalse(bool("/\\d+/.test('abc')"));
    }

    @Test
    public void test_flag_properties() {
        assertTrue(bool("/a/g.global"));
        assertTrue(bool("/a/i.ignoreCase"));
        assertTrue(bool("/a/m.multiline"));
        assertFalse(bool("/a/.sticky"));
        assertEquals(0, num("/a/g.lastIndex"));
    }

    @Test
    public void test_last_index_assignable() {
        final var source = """
                const re = /\\d/g;
                re.exec('a1b2');
                re.lastIndex = 0;
                re.exec('a1b2').index
                """;
        assertEquals(1, num(source));
    }

    @Test
    public void test_invalid_pattern_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('(')"));
    }

    @Test
    public void test_dotall_and_unknown_property() {
        assertTrue(bool("/a/s.dotAll"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("/a/.unknownProp"));
    }

    @Test
    public void test_constructor_no_args() {
        assertEquals("", str("new RegExp().source"));
    }

    @Test
    public void test_test_no_arg() {
        assertTrue(bool("/undefined/.test()"));
    }

    @Test
    public void test_escape_syntax_characters() {
        assertEquals("\\.\\*\\+", str("RegExp.escape('.*+')"));
        assertTrue(bool("new RegExp(RegExp.escape('a.b')).test('a.b')"));
        assertFalse(bool("new RegExp(RegExp.escape('a.b')).test('axb')"));
    }

    @Test
    public void test_escape_first_char() {
        assertEquals("\\x61bc", str("RegExp.escape('abc')"));
        assertTrue(bool("new RegExp(RegExp.escape('abc')).test('abc')"));
    }

    // ECMA-262 WhiteSpace is not java's: NBSP, NNBSP and the byte order mark are escaped too, and a
    @Test
    public void test_escape_whitespace_and_lone_surrogates() {
        assertEquals("\\ufeff\\x20\\xa0\\u202f", str("RegExp.escape('\\ufeff\\u0020\\u00a0\\u202f')"));
        assertEquals("\\ud800", str("RegExp.escape('\\ud800')"));
        assertEquals("\\udfff", str("RegExp.escape('\\udfff')"));
        assertEquals("2", str("String(RegExp.escape('\\ud800\\udc00').length)"));
        assertEquals("\\u2028", str("RegExp.escape('\\u2028')"));
    }

    @Test
    public void test_escape_non_string_throws() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("RegExp.escape(5)"));
    }

    @Test
    public void test_escape_whitespace() {
        assertEquals("\\tx", str("RegExp.escape(String.fromCharCode(9) + 'x')"));
    }

    @Test
    public void test_u_and_v_flags_conflict() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('x', 'uv')"));
    }

    @Test
    public void test_unicode_property_general_category() {
        assertTrue(bool("/\\p{L}/u.test('a')"));
        assertFalse(bool("/\\p{L}/u.test('3')"));
        assertTrue(bool("/\\p{Letter}/u.test('a')"));
        assertTrue(bool("/\\p{Decimal_Number}/u.test('7')"));
        assertTrue(bool("/\\p{gc=Nd}/u.test('5')"));
    }

    @Test
    public void test_unicode_property_scripts_and_binary() {
        assertTrue(bool("/\\p{Script=Greek}/u.test('\\u03B1')"));
        assertFalse(bool("/\\p{Script=Greek}/u.test('a')"));
        assertTrue(bool("/\\p{sc=Latin}/u.test('a')"));
        assertTrue(bool("/\\p{Alphabetic}/u.test('a')"));
        assertTrue(bool("/\\p{White_Space}/u.test(' ')"));
        assertTrue(bool("/\\p{L}/v.test('a')"));
    }

    @Test
    public void test_unicode_property_negation() {
        assertTrue(bool("/\\P{L}/u.test('3')"));
        assertFalse(bool("/\\P{L}/u.test('a')"));
    }

    // \d stays ASCII in u-mode (UNICODE_CHARACTER_CLASS deliberately not enabled)
    @Test
    public void test_predefined_classes_stay_ascii_in_unicode_mode() {
        assertTrue(bool("/^\\d$/u.test('3')"));
        assertFalse(bool("/^\\d$/u.test('\\u0663')"));
    }

    @Test
    public void test_unsupported_unicode_property_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("/\\p{Emoji}/u"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("/\\p{Foo=Bar}/u"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("/\\p{Script=Nonsense}/u"));
    }

    @Test
    public void test_unicode_accessors() {
        assertTrue(bool("/a/u.unicode"));
        assertFalse(bool("/a/.unicode"));
        assertTrue(bool("/a/v.unicodeSets"));
    }

    @Test
    public void escapeRendersControlCharactersInTheirNamedForm() {
        assertEquals("\\t", str("RegExp.escape('\\t')"));
        assertEquals("\\n", str("RegExp.escape('\\n')"));
        assertEquals("\\v", str("RegExp.escape('\\v')"));
        assertEquals("\\f", str("RegExp.escape('\\f')"));
        assertEquals("\\r", str("RegExp.escape('\\r')"));
    }

    @Test
    public void escapeHexEncodesPunctuatorsAndOtherWhitespace() {
        assertEquals("\\x2c", str("RegExp.escape(',')"));
        assertEquals("\\x20", str("RegExp.escape(' ')"));
        assertEquals("\\u1680", str("RegExp.escape('\\u1680')"));
        assertEquals("\\u2028", str("RegExp.escape('\\u2028')"));
        assertEquals("\\u2029", str("RegExp.escape('\\u2029')"));
    }

    @Test
    public void flagsIsGenericAndCanonicallyOrdered() {
        assertEquals("dgimsuy", str("new RegExp('', 'yusmigd').flags"));
        assertEquals("dgimsuvy",
                str("const get = Object.getOwnPropertyDescriptor(RegExp.prototype, 'flags').get;"
                        + " get.call({ hasIndices: 1, global: 1, ignoreCase: 1, multiline: 1, dotAll: 1, unicode: 1,"
                        + " unicodeSets: 1, sticky: 1 })"));
        assertEquals("dgimsuy",
                str("let calls = ''; const re = {"
                        + " get hasIndices() { calls += 'd'; return 1; }, get global() { calls += 'g'; return 1; },"
                        + " get ignoreCase() { calls += 'i'; return 1; }, get multiline() { calls += 'm'; return 1; },"
                        + " get dotAll() { calls += 's'; return 1; }, get unicode() { calls += 'u'; return 1; },"
                        + " get sticky() { calls += 'y'; return 1; } };"
                        + " Object.getOwnPropertyDescriptor(RegExp.prototype, 'flags').get.call(re); calls"));
    }

    @Test
    public void flagsReadsTheFlagPropertiesOffTheReceiver() {
        assertEquals("i",
                str("const r = /a/; Object.defineProperty(r, 'ignoreCase', { get() { return true; } });" + " r.flags"));
        assertEquals("TypeError",
                str("let caught = 'none'; const r = /a/;"
                        + " Object.defineProperty(r, 'global', { get() { throw new TypeError('boom'); } });"
                        + " try { r[Symbol.match](''); } catch (e) { caught = e.constructor.name; } caught"));
        assertEquals("/a/i", str("const r = /a/; Object.defineProperty(r, 'ignoreCase', { get() { return true; } });"
                + " r.toString()"));
    }

    @Test
    public void assigningAFlagIsRefusedUnlessShadowed() {
        assertEquals("TypeError", str("let caught = 'none'; const r = /a/g;"
                + " try { r.global = false; } catch (e) { caught = e.constructor.name; } caught"));
        assertEquals("false", str("const r = /a/g; Object.defineProperty(r, 'global', { writable: true });"
                + " r.global = false; String(r.global)"));
    }

    @Test
    public void stringIteratorNextRejectsAForeignReceiver() {
        assertEquals("TypeError", str("let caught = 'none'; const proto = Object.getPrototypeOf('a'.matchAll(/a/g));"
                + " try { proto.next.call({}); } catch (e) { caught = e.constructor.name; } caught"));
    }

    // %RegExp%[Symbol.species] is a real, discoverable getter (test262 built-ins/Function/prototype/
    // toString/symbol-named-builtins.js); speciesConstructor's fallback already produced the same result.
    @Test
    public void speciesIsADiscoverableGetterReturningTheReceiver() {
        assertTrue(bool("typeof Object.getOwnPropertyDescriptor(RegExp, Symbol.species).get === 'function'"));
        assertTrue(bool("RegExp[Symbol.species] === RegExp"));
        assertFalse(bool("Object.getOwnPropertyDescriptor(RegExp, Symbol.species).enumerable"));
        assertTrue(bool("Object.getOwnPropertyDescriptor(RegExp, Symbol.species).configurable"));
    }

    @Test
    public void constructorAcceptsARegexpLikeObject() {
        assertEquals("a+", str("new RegExp({ source: 'a+', flags: 'g', [Symbol.match]: true }).source"));
        assertEquals("g", str("new RegExp({ source: 'a+', flags: 'g', [Symbol.match]: true }).flags"));
    }
}
