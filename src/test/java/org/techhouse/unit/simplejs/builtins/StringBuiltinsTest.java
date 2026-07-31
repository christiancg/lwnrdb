package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class StringBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // String is callable as a coercion function
    @Test
    public void test_string_coercion() {
        assertEquals("42", str("String(42)"));
        assertEquals("", str("String()"));
    }

    // slice and substring extract ranges, honoring negatives and swaps
    @Test
    public void test_slice_substring() {
        assertEquals("bc", str("'abcd'.slice(1, 3)"));
        assertEquals("cd", str("'abcd'.slice(-2)"));
        assertEquals("ab", str("'abcd'.substring(2, 0)"));
    }

    // split divides on a literal separator or into characters
    @Test
    public void test_split() {
        assertEquals("a|b|c", str("'a,b,c'.split(',').join('|')"));
        assertEquals("a|b|c", str("'abc'.split('').join('|')"));
        assertEquals(1, num("'abc'.split().length"));
    }

    // replace swaps the first literal occurrence
    @Test
    public void test_replace() {
        assertEquals("a_b-c", str("'a-b-c'.replace('-', '_')"));
        assertEquals("abc", str("'abc'.replace('x', 'y')"));
    }

    // case, trim, includes, prefixes and padding
    @Test
    public void test_case_trim_predicates_pad() {
        assertEquals("ABC", str("'abc'.toUpperCase()"));
        assertEquals("abc", str("'ABC'.toLowerCase()"));
        assertEquals("hi", str("'  hi  '.trim()"));
        assertTrue(bool("'hello'.includes('ell')"));
        assertTrue(bool("'hello'.startsWith('he')"));
        assertTrue(bool("'hello'.endsWith('lo')"));
        assertEquals("00abc", str("'abc'.padStart(5, '0')"));
        assertEquals("abc", str("'abc'.padStart(2, '0')"));
    }

    // repeat, charAt and indexOf
    @Test
    public void test_repeat_charat_indexof() {
        assertEquals("ababab", str("'ab'.repeat(3)"));
        assertEquals("b", str("'abc'.charAt(1)"));
        assertEquals("", str("'abc'.charAt(9)"));
        assertEquals(2, num("'abc'.indexOf('c')"));
    }

    // repeat with a negative count throws a RangeError
    @Test
    public void test_repeat_negative_throws() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("'a'.repeat(-1)"));
    }

    // replace with a regex swaps the first match, or every match with the g flag
    @Test
    public void test_replace_regex() {
        assertEquals("a#b2", str("'a1b2'.replace(/\\d/, '#')"));
        assertEquals("a#b#", str("'a1b2'.replace(/\\d/g, '#')"));
    }

    // replace expands $1, $<name> and $& substitution tokens
    @Test
    public void test_replace_tokens() {
        assertEquals("01-2024", str("'2024-01'.replace(/(\\d+)-(\\d+)/, '$2-$1')"));
        assertEquals("[a]", str("'a'.replace(/a/, '[$&]')"));
        assertEquals("2024", str("'2024-01'.replace(/(?<y>\\d+)-(?<m>\\d+)/, '$<y>')"));
        assertEquals("a$xb", str("'a?b'.replace(/\\?/, '$x')"));
    }

    // replace accepts a function replacer receiving the match
    @Test
    public void test_replace_function() {
        assertEquals("A1b2", str("'a1b2'.replace(/[a-z]/, (m) => m.toUpperCase())"));
        assertEquals("A1B2", str("'a1b2'.replace(/[a-z]/g, (m) => m.toUpperCase())"));
    }

    // replaceAll swaps every occurrence, literal or regex, incl. a function
    @Test
    public void test_replace_all() {
        assertEquals("a_b_c", str("'a-b-c'.replaceAll('-', '_')"));
        assertEquals("###", str("'a1b'.replaceAll(/./g, '#')"));
        assertEquals("XbX", str("'aba'.replaceAll('a', 'X')"));
        assertEquals("AbA", str("'aba'.replaceAll(/a/g, (m) => m.toUpperCase())"));
    }

    // split on a regex divides the string
    @Test
    public void test_split_regex() {
        assertEquals("a|b|c", str("'a1b2c'.split(/\\d/).join('|')"));
    }

    // match returns the first match (non-global) or every match (global)
    @Test
    public void test_match() {
        assertEquals("12", str("'a12b'.match(/\\d+/)[0]"));
        assertEquals("1,2,3", str("'1a2b3'.match(/\\d/g).join(',')"));
        assertInstanceOf(JsNull.class, Interpreter.run("'abc'.match(/\\d/)"));
    }

    // matchAll yields every match with its capture groups
    @Test
    public void test_match_all() {
        assertEquals(2, num("'a1b2'.matchAll(/\\d/g).length"));
        assertEquals("1", str("'a1b2'.matchAll(/(\\d)/g)[0][1]"));
    }

    // search returns the index of the first match or -1
    @Test
    public void test_search() {
        assertEquals(1, num("'a1b'.search(/\\d/)"));
        assertEquals(-1, num("'abc'.search(/\\d/)"));
    }

    // a literal replace/replaceAll may use a function replacer with the offset
    @Test
    public void test_replace_literal_function() {
        assertEquals("a_1b", str("'a-b'.replace('-', (m, i) => '_' + i)"));
        assertEquals("aX1bX3", str("'a1b1'.replaceAll('1', (m, i) => 'X' + i)"));
    }

    // replaceAll with an empty search returns the string unchanged
    @Test
    public void test_replace_all_empty_search() {
        assertEquals("abc", str("'abc'.replaceAll('', 'x')"));
    }

    // a zero-width global regex replace inserts around every position
    @Test
    public void test_replace_zero_width() {
        assertEquals("-a-b-c-", str("'abc'.replace(/x*/g, '-')"));
    }

    // a regex function replacer receives capture groups and the offset
    @Test
    public void test_replace_regex_function_groups() {
        assertEquals("a11", str("'a1'.replace(/(\\d)/, (m, g1) => g1 + g1)"));
        assertEquals("a1b3", str("'a1b2'.replace(/\\d/g, (m, i) => i)"));
    }

    // replace expands $$, $` and $' substitution tokens
    @Test
    public void test_replace_special_tokens() {
        assertEquals("a$c", str("'abc'.replace(/b/, '$$')"));
        assertEquals("a[a]c", str("'abc'.replace(/b/, '[$`]')"));
        assertEquals("a[c]c", str("'abc'.replace(/b/, \"[$']\")"));
    }

    // an unterminated $< token is left literal, and a two-digit group index resolves
    @Test
    public void test_replace_token_edges() {
        assertEquals("$<b", str("'ab'.replace(/a/, '$<x')"));
        assertEquals("j", str("'abcdefghij'.replace(/(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)/, '$10')"));
    }

    // match returns null for a global pattern with no matches
    @Test
    public void test_match_global_none() {
        assertInstanceOf(JsNull.class, Interpreter.run("'abc'.match(/\\d/g)"));
    }

    // matchAll over a zero-width global pattern still terminates
    @Test
    public void test_match_all_zero_width() {
        assertEquals(3, num("'ab'.matchAll(/x*/g).length"));
    }

    // match/search coerce a string argument into a regex
    @Test
    public void test_string_arg_coercion() {
        assertEquals("b", str("'abc'.match('b')[0]"));
        assertEquals(1, num("'a1b'.search('\\\\d')"));
    }

    // String.raw concatenates the raw segments with the interpolated substitutions
    @Test
    public void test_string_raw_concatenates_raw_and_substitutions() {
        final var source = """
                const strings = { raw: ['a\\\\n', 'b', 'c'] };
                String.raw(strings, 1, 2)
                """;
        assertEquals("a\\n1b2c", str(source));
    }

    // String.raw returns an empty string when the raw segments are missing or empty
    @Test
    public void test_string_raw_empty() {
        assertEquals("", str("String.raw({ raw: [] })"));
        assertEquals("", str("String.raw({})"));
        assertEquals("", str("String.raw()"));
    }

    // charCodeAt/codePointAt return unit values or NaN/undefined out of range
    @Test
    public void test_charcode_codepoint() {
        assertEquals(97, num("'abc'.charCodeAt(0)"));
        assertTrue(Double.isNaN(num("'abc'.charCodeAt(9)")));
        assertEquals(97, num("'abc'.codePointAt(0)"));
        assertTrue(bool("'abc'.codePointAt(9) === undefined"));
    }

    // at indexes from the end with negatives
    @Test
    public void test_at() {
        assertEquals("c", str("'abc'.at(-1)"));
        assertEquals("a", str("'abc'.at(0)"));
        assertTrue(bool("'abc'.at(9) === undefined"));
    }

    // padEnd, trimStart and trimEnd
    @Test
    public void test_padend_trim() {
        assertEquals("abc00", str("'abc'.padEnd(5, '0')"));
        assertEquals("abc", str("'abc'.padEnd(2, '0')"));
        assertEquals("hi  ", str("'  hi  '.trimStart()"));
        assertEquals("  hi", str("'  hi  '.trimEnd()"));
    }

    // normalize, localeCompare and concat
    @Test
    public void test_normalize_localecompare_concat() {
        assertEquals("abc", str("'abc'.normalize()"));
        assertEquals(-1, num("'a'.localeCompare('b')"));
        assertEquals(1, num("'b'.localeCompare('a')"));
        assertEquals(0, num("'a'.localeCompare('a')"));
        assertEquals("abcd", str("'ab'.concat('c', 'd')"));
    }

    // String.fromCharCode and fromCodePoint build strings from code units/points
    @Test
    public void test_fromcharcode_fromcodepoint() {
        assertEquals("ABC", str("String.fromCharCode(65, 66, 67)"));
        assertEquals("abc", str("String.fromCodePoint(97, 98, 99)"));
    }

    // padEnd with an empty pad returns the value unchanged
    @Test
    public void test_padend_empty_pad() {
        assertEquals("abc", str("'abc'.padEnd(5, '')"));
    }

    // at and charCodeAt out-of-range boundaries
    @Test
    public void test_at_charcode_out_of_range() {
        assertTrue(bool("'abc'.at(-9) === undefined"));
        assertTrue(Double.isNaN(num("'abc'.charCodeAt(-1)")));
    }

    // isWellFormed is true for normal strings and valid surrogate pairs, false for lone surrogates
    @Test
    public void test_is_well_formed() {
        assertTrue(bool("'abc'.isWellFormed()"));
        assertTrue(bool("String.fromCharCode(0xD83D, 0xDE00).isWellFormed()"));
        assertFalse(bool("String.fromCharCode(0xD800).isWellFormed()"));
        assertFalse(bool("String.fromCharCode(0xDC00).isWellFormed()"));
        assertFalse(bool("('a' + String.fromCharCode(0xD800) + 'b').isWellFormed()"));
    }

    // toWellFormed replaces lone surrogates with U+FFFD and leaves valid text untouched
    @Test
    public void test_to_well_formed() {
        assertEquals("abc", str("'abc'.toWellFormed()"));
        assertEquals(65533, num("String.fromCharCode(0xD800).toWellFormed().charCodeAt(0)"));
        assertEquals(3, num("('a' + String.fromCharCode(0xDC00) + 'b').toWellFormed().length"));
        assertEquals(2, num("String.fromCharCode(0xD83D, 0xDE00).toWellFormed().length"));
    }
}
