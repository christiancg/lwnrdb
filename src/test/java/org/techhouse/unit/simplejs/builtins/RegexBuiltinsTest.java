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

    // the v (unicodeSets) flag compiles and matches
    @Test
    public void test_v_flag_matches() {
        assertTrue(bool("/[a-z]+/v.test('abc')"));
        assertEquals("v", str("/x/v.flags"));
    }

    // the u and v flags are mutually exclusive
    @Test
    public void test_u_and_v_flags_conflict() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('x', 'uv')"));
    }

    // general-category property escapes: short codes pass through, long names translate to short
    @Test
    public void test_unicode_property_general_category() {
        assertTrue(bool("/\\p{L}/u.test('a')"));
        assertFalse(bool("/\\p{L}/u.test('3')"));
        assertTrue(bool("/\\p{Letter}/u.test('a')"));
        assertTrue(bool("/\\p{Decimal_Number}/u.test('7')"));
        assertTrue(bool("/\\p{gc=Nd}/u.test('5')"));
    }

    // Script= / sc= and binary properties are translated to their java.util.regex equivalents
    @Test
    public void test_unicode_property_scripts_and_binary() {
        assertTrue(bool("/\\p{Script=Greek}/u.test('\\u03B1')"));
        assertFalse(bool("/\\p{Script=Greek}/u.test('a')"));
        assertTrue(bool("/\\p{sc=Latin}/u.test('a')"));
        assertTrue(bool("/\\p{Alphabetic}/u.test('a')"));
        assertTrue(bool("/\\p{White_Space}/u.test(' ')"));
        assertTrue(bool("/\\p{L}/v.test('a')"));
    }

    // \P negates the property; the whole class still resolves
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

    // unsupported or unknown Unicode properties are rejected with a SyntaxError
    @Test
    public void test_unsupported_unicode_property_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("/\\p{Emoji}/u"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("/\\p{Foo=Bar}/u"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("/\\p{Script=Nonsense}/u"));
    }

    // The d flag adds an indices array for numbered groups
    @Test
    public void test_indices_for_numbered_groups() {
        assertEquals(1, num("/(b)/d.exec('abc').indices[0][0]"));
        assertEquals(2, num("/(b)/d.exec('abc').indices[0][1]"));
        assertEquals(1, num("/(b)/d.exec('abc').indices[1][0]"));
        assertTrue(bool("/b/d.exec('abc').indices.groups === undefined"));
        assertTrue(bool("/a/d.hasIndices"));
        assertFalse(bool("/a/.hasIndices"));
    }

    // Named groups appear under indices.groups
    @Test
    public void test_indices_for_named_groups() {
        assertEquals(1, num("/(?<w>b)/d.exec('abc').indices.groups.w[0]"));
        assertEquals(2, num("/(?<w>b)/d.exec('abc').indices.groups.w[1]"));
    }

    // A non-participating group has an undefined entry
    @Test
    public void test_indices_for_non_participating_group() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("/b(z)?/d.exec('abc').indices[1]"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("/(?<w>z)?b/d.exec('abc').indices.groups.w"));
    }

    // Without the flag there is no indices array
    @Test
    public void test_no_indices_without_flag() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("/b/.exec('abc').indices"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("'abc'.match(/b/).indices"));
    }

    // match and matchAll carry indices when the flag is present
    @Test
    public void test_indices_through_string_methods() {
        assertEquals(1, num("'abc'.match(/b/d).indices[0][0]"));
        assertEquals(1, num("'abc'.matchAll(/b/dg)[0].indices[0][0]"));
    }
}
