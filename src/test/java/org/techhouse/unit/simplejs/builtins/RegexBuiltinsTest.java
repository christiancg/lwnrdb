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
import org.techhouse.simplejs.values.JsNull;
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

    // RegExp constructor builds a regex from a string pattern and flags
    @Test
    public void test_constructor_from_string() {
        assertTrue(bool("new RegExp('a.c').test('axc')"));
        assertTrue(bool("RegExp('a', 'i').test('A')"));
    }

    // RegExp constructor clones another regex, optionally overriding flags
    @Test
    public void test_constructor_clone() {
        assertEquals("i", str("new RegExp(/a/i).flags"));
        assertEquals("g", str("new RegExp(/a/i, 'g').flags"));
        assertEquals("a", str("new RegExp(/a/i).source"));
    }

    // test reports whether the pattern matches
    @Test
    public void test_test_method() {
        assertTrue(bool("/\\d+/.test('abc123')"));
        assertFalse(bool("/\\d+/.test('abc')"));
    }

    // exec returns the match with index and captured groups
    @Test
    public void test_exec_groups() {
        assertEquals("2024", str("/(\\d+)-(\\d+)/.exec('2024-01')[1]"));
        assertEquals("01", str("/(\\d+)-(\\d+)/.exec('2024-01')[2]"));
        assertEquals(0, num("/(\\d+)/.exec('12ab').index"));
    }

    // exec exposes named capture groups under .groups
    @Test
    public void test_exec_named_groups() {
        assertEquals("2024", str("/(?<year>\\d+)-(?<month>\\d+)/.exec('2024-01').groups.year"));
        assertEquals("01", str("/(?<year>\\d+)-(?<month>\\d+)/.exec('2024-01').groups.month"));
    }

    // exec returns null when there is no match
    @Test
    public void test_exec_no_match() {
        assertInstanceOf(JsNull.class, Interpreter.run("/z/.exec('abc')"));
    }

    // a global exec advances lastIndex across calls
    @Test
    public void test_exec_global_advances() {
        final var source = """
                const re = /\\d/g;
                re.exec('a1b2');
                re.exec('a1b2').index
                """;
        assertEquals(3, num(source));
    }

    // regex property accessors reflect the flags and lastIndex
    @Test
    public void test_flag_properties() {
        assertTrue(bool("/a/g.global"));
        assertTrue(bool("/a/i.ignoreCase"));
        assertTrue(bool("/a/m.multiline"));
        assertFalse(bool("/a/.sticky"));
        assertEquals(0, num("/a/g.lastIndex"));
    }

    // assigning lastIndex resets the stateful matching position
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

    // an invalid pattern in the RegExp constructor throws a SyntaxError
    @Test
    public void test_invalid_pattern_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('(')"));
    }

    // a sticky regex anchors the match at lastIndex
    @Test
    public void test_sticky_exec() {
        assertEquals(0, num("/a/y.exec('a').index"));
        assertInstanceOf(JsNull.class, Interpreter.run("/a/y.exec('ba')"));
    }

    // the dotAll accessor and an unknown property resolve
    @Test
    public void test_dotall_and_unknown_property() {
        assertTrue(bool("/a/s.dotAll"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("/a/.unknownProp"));
    }

    // RegExp with no arguments builds an empty-source regex
    @Test
    public void test_constructor_no_args() {
        assertEquals("", str("new RegExp().source"));
    }

    // an optional group that does not participate is undefined
    @Test
    public void test_optional_group_undefined() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("/(a)(b)?/.exec('a')[2]"));
    }

    // test with no argument matches against the string "undefined"
    @Test
    public void test_test_no_arg() {
        assertTrue(bool("/undefined/.test()"));
    }

    // a failed global exec resets lastIndex to zero
    @Test
    public void test_global_exec_no_match_resets() {
        final var source = """
                const re = /z/g;
                re.exec('abc');
                re.lastIndex
                """;
        assertEquals(0, num(source));
    }

    // RegExp.escape escapes syntax characters so the result matches the literal string
    @Test
    public void test_escape_syntax_characters() {
        assertEquals("\\.\\*\\+", str("RegExp.escape('.*+')"));
        assertTrue(bool("new RegExp(RegExp.escape('a.b')).test('a.b')"));
        assertFalse(bool("new RegExp(RegExp.escape('a.b')).test('axb')"));
    }

    // RegExp.escape hex-escapes an alphanumeric first character so concatenation stays safe
    @Test
    public void test_escape_first_char() {
        assertEquals("\\x61bc", str("RegExp.escape('abc')"));
        assertTrue(bool("new RegExp(RegExp.escape('abc')).test('abc')"));
    }

    // RegExp.escape rejects a non-string argument
    @Test
    public void test_escape_non_string_throws() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("RegExp.escape(5)"));
    }

    // RegExp.escape emits named escapes for whitespace control characters
    @Test
    public void test_escape_whitespace() {
        assertEquals("\\tx", str("RegExp.escape(String.fromCharCode(9) + 'x')"));
    }
}
