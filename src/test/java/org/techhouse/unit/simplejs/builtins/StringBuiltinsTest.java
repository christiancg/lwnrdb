package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
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
        assertEquals(2, num("[...'a1b2'.matchAll(/\\d/g)].length"));
        assertEquals("1", str("[...'a1b2'.matchAll(/(\\d)/g)][0][1]"));
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

    @Test
    public void replaceAllHandlesEmptySearchString() {
        assertEquals("xaxbxcx", str("'abc'.replaceAll('', 'x')"));
        assertEquals("-a-a-a-", str("'aaa'.replaceAll('', '-')"));
        assertEquals("x", str("''.replaceAll('', 'x')"));
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
        assertEquals("$<xb", str("'ab'.replace(/a/, '$<x')"));
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
        assertEquals(3, num("[...'ab'.matchAll(/x*/g)].length"));
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
        assertThrows(TypeErrorException.class, () -> Interpreter.run("String.raw({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("String.raw()"));
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

    // Collator-backed localeCompare orders an accented character next to its base, not by code point
    @Test
    public void test_locale_compare_collation() {
        assertTrue(num("'á'.localeCompare('b')") < 0);
        assertTrue(num("'a'.localeCompare('á')") < 0);
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

    // replace/replaceAll delegate to a [Symbol.replace] method on the argument, receiving (string, replacement)
    @Test
    public void test_symbol_replace_delegation() {
        assertEquals("abc/x", str("let p = { [Symbol.replace](s, r) { return s + '/' + r; } }; 'abc'.replace(p, 'x')"));
        assertEquals("R", str("let p = { [Symbol.replace](s, r) { return 'R'; } }; 'abc'.replaceAll(p, 'x')"));
    }

    // split delegates to a [Symbol.split] method on the argument
    @Test
    public void test_symbol_split_delegation() {
        assertEquals("HI", str("let p = { [Symbol.split](s) { return s.toUpperCase(); } }; 'hi'.split(p)"));
    }

    // match and search delegate to their well-known-symbol methods on the argument
    @Test
    public void test_symbol_match_and_search_delegation() {
        assertEquals(7, num("let p = { [Symbol.match](s) { return 7; } }; 'abc'.match(p)"));
        assertEquals(9, num("let p = { [Symbol.search](s) { return 9; } }; 'abc'.search(p)"));
    }

    // A plain string/regex argument keeps the built-in behavior (no symbol method present)
    @Test
    public void test_plain_argument_not_delegated() {
        assertEquals("axc", str("'abc'.replace('b', 'x')"));
        assertEquals(1, num("'abc'.search(/b/)"));
    }

    // split honours its limit argument
    @Test
    public void test_split_limit() {
        assertEquals("a|b", str("'a,b,c'.split(',', 2).join('|')"));
        assertEquals(0, num("'a,b,c'.split(',', 0).length"));
        assertEquals(3, num("'a,b,c'.split(',', -1).length"));
        assertEquals(3, num("'a,b,c'.split(',').length"));
        assertEquals("ab", str("'abc'.split('', 2).join('')"));
        assertEquals(1, num("'a,b,'.split(',', 1).length"));
    }

    // A regex separator keeps trailing empties before the limit is applied
    @Test
    public void test_split_limit_with_regex() {
        assertEquals(4, num("'a1b2c3'.split(/[0-9]/).length"));
        assertEquals(2, num("'a1b2c3'.split(/[0-9]/, 2).length"));
        assertEquals("", str("'a,,'.split(/,/).pop()"));
    }

    // $$ in a replacement yields a literal dollar for a string search
    @Test
    public void test_dollar_escape_in_replacement() {
        assertEquals("$", str("'a'.replace('a', '$$')"));
        assertEquals("[a]", str("'a'.replace('a', '[$&]')"));
        assertEquals("xx+yy", str("'xay'.replace('a', '$`+$\\'')"));
        assertEquals("$x", str("'a'.replace('a', '$x')"));
        assertEquals("$", str("'aa'.replaceAll('aa', '$$')"));
    }

    // matchAll rejects a non-global regex
    @Test
    public void test_match_all_requires_global() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("'aa'.matchAll(/a/)"));
        assertEquals(2, num("[...'aa'.matchAll(/a/g)].length"));
        assertEquals(2, num("[...'aa'.matchAll('a')].length"));
    }

    // Annex-B substr handles negative and absent lengths
    @Test
    public void test_substr() {
        assertEquals("de", str("'abcdef'.substr(-3, 2)"));
        assertEquals("cdef", str("'abcdef'.substr(2)"));
        assertEquals("", str("'abcdef'.substr(2, 0)"));
        assertEquals("", str("'abcdef'.substr(2, -1)"));
        assertEquals("abc", str("'abcdef'.substr(-10, 3)"));
        assertEquals("", str("'abcdef'.substr(10, 3)"));
        assertEquals("ef", str("'abcdef'.substr(4, 10)"));
    }

    // Annex-B trim aliases and locale case conversion
    @Test
    public void test_annex_b_aliases() {
        assertEquals("a ", str("'  a '.trimLeft()"));
        assertEquals("  a", str("'  a  '.trimRight()"));
        assertEquals("ABC", str("'abc'.toLocaleUpperCase()"));
        assertEquals("abc", str("'ABC'.toLocaleLowerCase()"));
    }

    // String(symbol) returns the descriptive string instead of throwing
    @Test
    public void test_string_of_symbol_returns_descriptive_string() {
        assertEquals("Symbol(x)", str("String(Symbol('x'))"));
    }

    // a symbol without a description describes as an empty pair of parentheses
    @Test
    public void test_string_of_symbol_without_description() {
        assertEquals("Symbol()", str("String(Symbol())"));
    }

    // a well-known symbol keeps its registered description
    @Test
    public void test_string_of_well_known_symbol() {
        assertEquals("Symbol(Symbol.iterator)", str("String(Symbol.iterator)"));
    }

    // implicit symbol coercion is still a TypeError
    @Test
    public void test_implicit_symbol_coercion_still_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'' + Symbol()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("`${Symbol()}`"));
    }

    // the String wrapper constructor still rejects a symbol
    @Test
    public void test_new_string_of_symbol_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new String(Symbol())"));
    }

    // String.prototype methods are generic: a non-string receiver (number, object with valueOf/
    // toString) is coerced via ToString rather than rejected; only null/undefined still throw
    @Test
    public void test_string_methods_generic_receiver() {
        assertEquals("[object Object]", str("String.prototype.trim.call({})"));
        assertEquals("5", str("String.prototype.charAt.call(5, 0)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("String.prototype.trim.call(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("String.prototype.trim.call(undefined)"));
    }

    // includes/startsWith/endsWith reject a RegExp argument, and a plain object with a throwing
    // @@match getter propagates that error rather than converting to string first
    @Test
    public void test_includes_start_ends_with_reject_regexp() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'abc'.includes(/b/)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'abc'.startsWith(/a/)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'abc'.endsWith(/c/)"));
        assertThrows(JsThrowException.class, () -> Interpreter.run("""
                var obj = {};
                Object.defineProperty(obj, Symbol.match, { get() { throw new TypeError('boom'); } });
                'abc'.endsWith(obj);
                """));
    }

    // indexOf/lastIndexOf honor the fromIndex/position argument
    @Test
    public void test_index_of_and_last_index_of_position() {
        assertEquals(3, num("'abcabc'.indexOf('a', 1)"));
        assertEquals(3, num("'abcabc'.lastIndexOf('a')"));
        assertEquals(0, num("'abcabc'.lastIndexOf('a', 2)"));
        assertEquals(-1, num("'abc'.indexOf('a', 5)"));
    }

    // fromCodePoint validates each argument is an integral Number in range, coercing via valueOf
    @Test
    public void test_from_code_point_validation() {
        assertEquals("a", str("String.fromCodePoint(97)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("String.fromCodePoint(1.5)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("String.fromCodePoint(-1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("String.fromCodePoint(0x110000)"));
        assertEquals(97, num("String.fromCodePoint({valueOf(){return 97;}}).charCodeAt(0)"));
    }

    // fromCharCode/fromCodePoint/raw report the spec length (1) despite the rest parameter
    @Test
    public void test_from_char_code_and_from_code_point_length() {
        assertEquals(1, num("String.fromCharCode.length"));
        assertEquals(1, num("String.fromCodePoint.length"));
        assertEquals(1, num("String.raw.length"));
    }

    // normalize rejects an invalid form with a RangeError instead of leaking the JDK exception
    @Test
    public void test_normalize_invalid_form_throws_range_error() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("'a'.normalize('bogus')"));
        assertEquals("a", str("'a'.normalize()"));
    }

    // replaceAll throws for a non-global RegExp search value
    @Test
    public void test_replace_all_non_global_regexp_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'abc'.replaceAll(/a/, 'x')"));
        assertEquals("xbc", str("'abc'.replaceAll(/a/g, 'x')"));
    }

    @Test
    public void startsWithHonoursPosition() {
        assertTrue(bool("'word'.startsWith('o', 1)"));
        assertFalse(bool("'word'.startsWith('o', 0)"));
        assertTrue(bool("'word'.startsWith('', 99)"));
    }

    @Test
    public void endsWithHonoursEndPosition() {
        assertTrue(bool("'word'.endsWith('or', 3)"));
        assertFalse(bool("'word'.endsWith('or', 4)"));
        assertTrue(bool("'word'.endsWith('word', undefined)"));
    }

    @Test
    public void includesHonoursPosition() {
        assertTrue(bool("'word'.includes('o', 1)"));
        assertFalse(bool("'word'.includes('w', 1)"));
    }

    @Test
    public void positionArgumentIsCoerced() {
        assertTrue(bool("'word'.startsWith('o', { valueOf: () => 1 })"));
        assertThrows(JsThrowException.class,
                () -> Interpreter.run("'word'.startsWith('o', { valueOf() { throw new Error('x'); } })"));
    }

    @Test
    public void trimUsesJsWhitespaceSet() {
        assertEquals("a", str("'\\u00a0\\ufeff\\u1680\\u2000\\u202f\\u205f\\u3000 a '.trim()"));
        assertEquals("a ", str("'\\u00a0a '.trimStart()"));
        assertEquals("\u00a0a", str("'\\u00a0a\\u00a0'.trimEnd()"));
        // U+001C-001F are Java whitespace but not JS whitespace, so they survive a trim
        assertEquals(3, num("'\\u001ca\\u001c'.trim().length"));
    }

    @Test
    public void matchDispatchesToSymbolMatch() {
        assertEquals("hit", str("'abc'.match({ [Symbol.match]: () => 'hit' })"));
        assertEquals("hit", str("'abc'.matchAll({ [Symbol.matchAll]: () => 'hit' })"));
        assertEquals(7, num("'abc'.search({ [Symbol.search]: () => 7 })"));
        assertEquals("hit", str("'abc'.replace({ [Symbol.replace]: () => 'hit' }, 'x')"));
        assertEquals("hit", str("'abc'.replaceAll({ [Symbol.replace]: () => 'hit' }, 'x')"));
        assertEquals("hit", str("'abc'.split({ [Symbol.split]: () => 'hit' })"));
    }

    @Test
    public void getSubstitutionLeavesUnresolvableTokensLiteral() {
        assertEquals("$<x>c", str("'abc'.replace(/ab/, '$<x>')"));
        assertEquals("$9c", str("'abc'.replace(/(a)b/, '$9')"));
        assertEquals("c", str("'abc'.replace(/(?<n>a)b/, '$<missing>')"));
    }

    @Test
    public void padHonoursExplicitUndefinedFill() {
        assertEquals("  a", str("'a'.padStart(3, undefined)"));
        assertEquals("a  ", str("'a'.padEnd(3, undefined)"));
    }

    // split/replace/replaceAll/match/search are generic: RequireObjectCoercible(this) runs, then the
    // well-known-symbol delegation attempt against the raw receiver/argument, and only once that is
    // ruled out does ToString(this) happen - so a poisoned receiver's toString must not fire when a
    // matching delegate exists, even when called via .call() with a non-string `this`.
    @Test
    public void genericDispatchDelegatesBeforeCoercingThePoisonedReceiver() {
        assertTrue(bool("""
                var poisoned = 0;
                var poison = { toString() { poisoned += 1; throw 'should not run'; } };
                var searchValue = { [Symbol.replace]: (o, r) => o === poison && r === poison };
                var result = ''.replaceAll.call(poison, searchValue, poison);
                result === true && poisoned === 0
                """));
        assertTrue(bool("""
                var poisoned = 0;
                var poison = { toString() { poisoned += 1; throw 'should not run'; } };
                var splitter = { [Symbol.split]: (o) => o === poison };
                var result = ''.split.call(poison, splitter);
                result === true && poisoned === 0
                """));
        assertTrue(bool("""
                var poisoned = 0;
                var poison = { toString() { poisoned += 1; throw 'should not run'; } };
                var matcher = { [Symbol.match]: (o) => o === poison };
                var result = ''.match.call(poison, matcher);
                result === true && poisoned === 0
                """));
    }

    // Once delegation is ruled out (a non-object searchValue), ToString(this) still has to happen
    // before ToString(searchValue) - the receiver-coercion order test262 pins down.
    @Test
    public void genericDispatchCoercesReceiverBeforeSeparatorWhenNoDelegate() {
        final var thrown = assertThrows(JsThrowException.class, () -> Interpreter.run("""
                var receiver = { toString() { throw 'receiver first'; } };
                var separator = { toString() { throw 'separator second'; }, valueOf() { throw 'separator second'; } };
                String.prototype.split.call(receiver, separator);
                """));
        assertEquals("receiver first", ((JsString) thrown.getValue()).getValue());
    }

    // replace/replaceAll coerce a non-callable replaceValue up front (spec step 6), even when the
    // search string is never found - a no-match result must still observe replaceValue's ToString.
    @Test
    public void replaceCoercesNonCallableReplacementEvenWithoutAMatch() {
        assertTrue(bool("""
                var calls = 0;
                var replaceValue = { toString() { calls += 1; return 'x'; } };
                var result = ''.replace('a', replaceValue);
                result === '' && calls === 1
                """));
        assertTrue(bool("""
                var calls = 0;
                var replaceValue = { toString() { calls += 1; return 'x'; } };
                var result = ''.replaceAll('a', replaceValue);
                result === '' && calls === 1
                """));
    }

    // A callable replaceValue is never stringified, matched or not - only its call result is coerced.
    @Test
    public void replaceLeavesACallableReplacementUncoerced() {
        assertTrue(bool("""
                var called = 0;
                var fn = () => { called += 1; return 'x'; };
                var result = 'a'.replace('a', fn);
                result === 'x' && called === 1
                """));
        assertTrue(bool("""
                var called = 0;
                var fn = () => { called += 1; return 'x'; };
                var result = ''.replace('a', fn);
                result === '' && called === 0
                """));
    }

    // A defined-but-non-callable well-known-symbol delegate is a TypeError (GetMethod step 4), not a
    // silent fall-through to the generic ToString path.
    @Test
    public void genericDispatchRejectsNonCallableDelegate() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("''.replaceAll.call('x', { [Symbol.replace]: 'nope' }, 'y')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("''.search.call('x', { [Symbol.search]: 42 })"));
    }
}
