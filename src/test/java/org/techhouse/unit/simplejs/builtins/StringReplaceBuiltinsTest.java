package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class StringReplaceBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_split() {
        assertEquals("a|b|c", str("'a,b,c'.split(',').join('|')"));
        assertEquals("a|b|c", str("'abc'.split('').join('|')"));
        assertEquals(1, num("'abc'.split().length"));
    }

    @Test
    public void test_replace() {
        assertEquals("a_b-c", str("'a-b-c'.replace('-', '_')"));
        assertEquals("abc", str("'abc'.replace('x', 'y')"));
    }

    @Test
    public void test_replace_regex() {
        assertEquals("a#b2", str("'a1b2'.replace(/\\d/, '#')"));
        assertEquals("a#b#", str("'a1b2'.replace(/\\d/g, '#')"));
    }

    @Test
    public void test_replace_tokens() {
        assertEquals("01-2024", str("'2024-01'.replace(/(\\d+)-(\\d+)/, '$2-$1')"));
        assertEquals("[a]", str("'a'.replace(/a/, '[$&]')"));
        assertEquals("2024", str("'2024-01'.replace(/(?<y>\\d+)-(?<m>\\d+)/, '$<y>')"));
        assertEquals("a$xb", str("'a?b'.replace(/\\?/, '$x')"));
    }

    @Test
    public void test_replace_function() {
        assertEquals("A1b2", str("'a1b2'.replace(/[a-z]/, (m) => m.toUpperCase())"));
        assertEquals("A1B2", str("'a1b2'.replace(/[a-z]/g, (m) => m.toUpperCase())"));
    }

    @Test
    public void test_replace_all() {
        assertEquals("a_b_c", str("'a-b-c'.replaceAll('-', '_')"));
        assertEquals("###", str("'a1b'.replaceAll(/./g, '#')"));
        assertEquals("XbX", str("'aba'.replaceAll('a', 'X')"));
        assertEquals("AbA", str("'aba'.replaceAll(/a/g, (m) => m.toUpperCase())"));
    }

    @Test
    public void test_split_regex() {
        assertEquals("a|b|c", str("'a1b2c'.split(/\\d/).join('|')"));
    }

    @Test
    public void test_match() {
        assertEquals("12", str("'a12b'.match(/\\d+/)[0]"));
        assertEquals("1,2,3", str("'1a2b3'.match(/\\d/g).join(',')"));
        assertInstanceOf(JsNull.class, Interpreter.run("'abc'.match(/\\d/)"));
    }

    @Test
    public void test_match_all() {
        assertEquals(2, num("[...'a1b2'.matchAll(/\\d/g)].length"));
        assertEquals("1", str("[...'a1b2'.matchAll(/(\\d)/g)][0][1]"));
    }

    @Test
    public void test_search() {
        assertEquals(1, num("'a1b'.search(/\\d/)"));
        assertEquals(-1, num("'abc'.search(/\\d/)"));
    }

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

    @Test
    public void test_replace_zero_width() {
        assertEquals("-a-b-c-", str("'abc'.replace(/x*/g, '-')"));
    }

    @Test
    public void test_replace_regex_function_groups() {
        assertEquals("a11", str("'a1'.replace(/(\\d)/, (m, g1) => g1 + g1)"));
        assertEquals("a1b3", str("'a1b2'.replace(/\\d/g, (m, i) => i)"));
    }

    @Test
    public void test_replace_special_tokens() {
        assertEquals("a$c", str("'abc'.replace(/b/, '$$')"));
        assertEquals("a[a]c", str("'abc'.replace(/b/, '[$`]')"));
        assertEquals("a[c]c", str("'abc'.replace(/b/, \"[$']\")"));
    }

    @Test
    public void test_replace_token_edges() {
        assertEquals("$<xb", str("'ab'.replace(/a/, '$<x')"));
        assertEquals("j", str("'abcdefghij'.replace(/(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)/, '$10')"));
    }

    @Test
    public void test_match_global_none() {
        assertInstanceOf(JsNull.class, Interpreter.run("'abc'.match(/\\d/g)"));
    }

    @Test
    public void test_match_all_zero_width() {
        assertEquals(3, num("[...'ab'.matchAll(/x*/g)].length"));
    }

    @Test
    public void test_symbol_replace_delegation() {
        assertEquals("abc/x", str("let p = { [Symbol.replace](s, r) { return s + '/' + r; } }; 'abc'.replace(p, 'x')"));
        assertEquals("R", str("let p = { [Symbol.replace](s, r) { return 'R'; } }; 'abc'.replaceAll(p, 'x')"));
    }

    @Test
    public void test_symbol_split_delegation() {
        assertEquals("HI", str("let p = { [Symbol.split](s) { return s.toUpperCase(); } }; 'hi'.split(p)"));
    }

    @Test
    public void test_symbol_match_and_search_delegation() {
        assertEquals(7, num("let p = { [Symbol.match](s) { return 7; } }; 'abc'.match(p)"));
        assertEquals(9, num("let p = { [Symbol.search](s) { return 9; } }; 'abc'.search(p)"));
    }

    @Test
    public void test_split_limit() {
        assertEquals("a|b", str("'a,b,c'.split(',', 2).join('|')"));
        assertEquals(0, num("'a,b,c'.split(',', 0).length"));
        assertEquals(3, num("'a,b,c'.split(',', -1).length"));
        assertEquals(3, num("'a,b,c'.split(',').length"));
        assertEquals("ab", str("'abc'.split('', 2).join('')"));
        assertEquals(1, num("'a,b,'.split(',', 1).length"));
    }

    @Test
    public void test_split_limit_with_regex() {
        assertEquals(4, num("'a1b2c3'.split(/[0-9]/).length"));
        assertEquals(2, num("'a1b2c3'.split(/[0-9]/, 2).length"));
        assertEquals("", str("'a,,'.split(/,/).pop()"));
    }

    @Test
    public void test_dollar_escape_in_replacement() {
        assertEquals("$", str("'a'.replace('a', '$$')"));
        assertEquals("[a]", str("'a'.replace('a', '[$&]')"));
        assertEquals("xx+yy", str("'xay'.replace('a', '$`+$\\'')"));
        assertEquals("$x", str("'a'.replace('a', '$x')"));
        assertEquals("$", str("'aa'.replaceAll('aa', '$$')"));
    }

    @Test
    public void test_match_all_requires_global() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class,
                () -> Interpreter.run("'aa'.matchAll(/a/)"));
        assertEquals(2, num("[...'aa'.matchAll(/a/g)].length"));
        assertEquals(2, num("[...'aa'.matchAll('a')].length"));
    }

    @Test
    public void test_string_of_symbol_returns_descriptive_string() {
        assertEquals("Symbol(x)", str("String(Symbol('x'))"));
    }

    @Test
    public void test_string_of_symbol_without_description() {
        assertEquals("Symbol()", str("String(Symbol())"));
    }

    @Test
    public void test_string_of_well_known_symbol() {
        assertEquals("Symbol(Symbol.iterator)", str("String(Symbol.iterator)"));
    }

    @Test
    public void test_implicit_symbol_coercion_still_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'' + Symbol()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("`${Symbol()}`"));
    }

    @Test
    public void test_new_string_of_symbol_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new String(Symbol())"));
    }

    @Test
    public void test_replace_all_non_global_regexp_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'abc'.replaceAll(/a/, 'x')"));
        assertEquals("xbc", str("'abc'.replaceAll(/a/g, 'x')"));
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
}
