package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
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

    // Spec requirement: strings that are canonically equivalent per Unicode normalization must
    // compare as 0, even when the underlying combining-mark order differs. Collator.getInstance()
    // defaults to NO_DECOMPOSITION, which would treat these as unequal without explicitly requesting
    // CANONICAL_DECOMPOSITION.
    @Test
    public void test_locale_compare_canonical_equivalence() {
        assertEquals(0, num("'\\u00E4\\u0323'.localeCompare('a\\u0323\\u0308')"),
                "a-with-diaeresis + dot-below == a + dot-below + diaeresis");
        assertEquals(0, num("'\\u00C7'.localeCompare('C\\u0327')"), "C-with-cedilla == C + combining cedilla");
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

    // A plain string/regex argument keeps the built-in behavior (no symbol method present)
    @Test
    public void test_plain_argument_not_delegated() {
        assertEquals("axc", str("'abc'.replace('b', 'x')"));
        assertEquals(1, num("'abc'.search(/b/)"));
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

    // Greek capital sigma lower-cases to the context-dependent final form (U+03C2) at the end of a
    // cased-letter run, and to the ordinary medial form (U+03C3) everywhere else (Unicode Default
    // Case Algorithm's Final_Sigma condition, SpecialCasing.txt)
    @Test
    public void test_to_lower_case_final_sigma() {
        assertEquals("σ", str("'\\u03A3'.toLowerCase()"));
        assertEquals("aς", str("'A\\u03A3'.toLowerCase()"));
        assertEquals("a.ς", str("'A.\\u03A3'.toLowerCase()"));
        assertEquals("a­ς", str("'A\\u00AD\\u03A3'.toLowerCase()"));
        assertEquals("a𝉂ς", str("'A\\uD834\\uDE42\\u03A3'.toLowerCase()"));
        assertEquals("ͅσ", str("'\\u0345\\u03A3'.toLowerCase()"));
        assertEquals("ᾳς", str("'\\u0391\\u0345\\u03A3'.toLowerCase()"));
        assertEquals("aσb", str("'A\\u03A3B'.toLowerCase()"));
        assertEquals("aσ.b", str("'A\\u03A3.b'.toLowerCase()"));
        assertEquals("aςͅ", str("'A\\u03A3\\u0345'.toLowerCase()"));
        assertEquals("aσͅα", str("'A\\u03A3\\u0345\\u0391'.toLowerCase()"));
        assertEquals("aς", str("'A\\u03A3'.toLocaleLowerCase()"));
        assertEquals("aσb", str("'A\\u03A3B'.toLocaleLowerCase()"));
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

    // A defined-but-non-callable well-known-symbol delegate is a TypeError (GetMethod step 4), not a
    // silent fall-through to the generic ToString path.
    @Test
    public void genericDispatchRejectsNonCallableDelegate() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("''.replaceAll.call('x', { [Symbol.replace]: 'nope' }, 'y')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("''.search.call('x', { [Symbol.search]: 42 })"));
    }
}
