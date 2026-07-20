package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
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
}
