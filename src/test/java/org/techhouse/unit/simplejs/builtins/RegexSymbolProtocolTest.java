package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class RegexSymbolProtocolTest {
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
    public void test_symbol_methods_are_functions() {
        assertTrue(bool("typeof RegExp.prototype[Symbol.match] === 'function'"));
        assertTrue(bool("typeof RegExp.prototype[Symbol.search] === 'function'"));
        assertTrue(bool("typeof RegExp.prototype[Symbol.replace] === 'function'"));
        assertTrue(bool("typeof RegExp.prototype[Symbol.split] === 'function'"));
    }

    @Test
    public void test_symbol_match_non_global() {
        assertEquals("a", str("/a/[Symbol.match]('abc')[0]"));
        assertInstanceOf(JsNull.class, Interpreter.run("/z/[Symbol.match]('abc')"));
    }

    @Test
    public void test_symbol_match_global_collects_all() {
        assertEquals("a,a,a", str("/a/g[Symbol.match]('aaa').join(',')"));
        assertInstanceOf(JsNull.class, Interpreter.run("/z/g[Symbol.match]('abc')"));
    }

    @Test
    public void test_symbol_match_global_advances_past_empty_match() {
        assertEquals(4, num("/(?:)/g[Symbol.match]('abc').length"));
    }

    @Test
    public void test_symbol_search() {
        assertEquals(1, num("/b/[Symbol.search]('abc')"));
        assertEquals(-1, num("/z/[Symbol.search]('abc')"));
    }

    @Test
    public void test_symbol_search_restores_lastindex() {
        assertEquals(2, num("""
                var r = /b/g;
                r.lastIndex = 2;
                var pos = r[Symbol.search]('abc');
                r.lastIndex
                """));
    }

    @Test
    public void test_symbol_replace_literal_and_function() {
        assertEquals("aXc", str("/b/[Symbol.replace]('abc', 'X')"));
        assertEquals("aXcXe", str("/b/g[Symbol.replace]('abcbe', 'X')"));
        assertEquals("a1c", str("/b/[Symbol.replace]('abc', (m) => '1')"));
    }

    @Test
    public void test_symbol_replace_capture_groups_and_dollar_patterns() {
        assertEquals("a-b-c", str("/(a)(b)(c)/[Symbol.replace]('abc', '$1-$2-$3')"));
        assertEquals("[a]bc", str("/a/[Symbol.replace]('abc', '[$&]')"));
        assertEquals("aabcc", str("/b/[Symbol.replace](\"abc\", \"$`$&$'\")"));
    }

    @Test
    public void test_symbol_replace_named_groups() {
        assertEquals("aX", str("/(?<x>a)/[Symbol.replace]('a', '$<x>X')"));
    }

    @Test
    public void test_symbol_split_basic() {
        assertEquals("a,b,c", str("/,/[Symbol.split]('a,b,c').join('|').replace(/\\|/g, ',')"));
        assertEquals("3", str("String(/,/[Symbol.split]('a,b,c').length)"));
    }

    @Test
    public void test_symbol_split_with_limit_and_captures() {
        assertEquals(2, num("/,/[Symbol.split]('a,b,c', 2).length"));
        assertEquals(0, num("/,/[Symbol.split]('a,b,c', 0).length"));
        assertTrue(bool("/(,)/[Symbol.split]('a,b').includes(',')"));
    }

    @Test
    public void test_symbol_methods_dispatch_through_custom_exec() {
        assertEquals(1, num("""
                var calls = 0;
                class R extends RegExp {
                  exec(s) { calls++; return null; }
                }
                new R('a')[Symbol.match]('a');
                calls
                """));
    }

    @Test
    public void test_prototype_flag_accessors_are_real_properties() {
        assertEquals("true", str(
                "String(typeof Object.getOwnPropertyDescriptor(RegExp.prototype, 'global').get" + " === 'function')"));
        assertEquals("true,false", str("String(/a/g.global) + ',' + /a/g.sticky"));
        assertEquals("gi", str("/a/gi.flags"));
    }

    @Test
    public void test_prototype_accessor_on_bare_prototype() {
        assertEquals("(?:)", str("RegExp.prototype.source"));
        assertEquals("undefined", str("String(RegExp.prototype.global)"));
    }

    @Test
    public void test_prototype_accessor_on_foreign_receiver_throws() {
        assertEquals("TypeError",
                str("let caught = 'none';"
                        + " try { Object.getOwnPropertyDescriptor(RegExp.prototype, 'global').get.call({}); }"
                        + " catch (e) { caught = e.constructor.name; } caught"));
    }

    @Test
    public void test_prototype_to_string() {
        assertEquals("/ab+c/gi", str("/ab+c/gi.toString()"));
    }

    private static final String RP = "RegExp.prototype";

    @Test
    public void symbolReplaceExpandsEveryDollarToken() {
        assertEquals("$", str(RP + "[Symbol.replace].call(/a/, 'a', '$$')"));
        assertEquals("[a]", str(RP + "[Symbol.replace].call(/a/, 'a', '[$&]')"));
        assertEquals("xy-xy", str(RP + "[Symbol.replace].call(/a/, 'xya', '-$`')"));
        assertEquals("-xyzxyz", str(RP + "[Symbol.replace].call(/a/, 'axyz', '-$\\'')"));
        assertEquals("$z", str(RP + "[Symbol.replace].call(/a/, 'a', '$z')"));
        assertEquals("$", str(RP + "[Symbol.replace].call(/a/, 'a', '$')"));
    }

    @Test
    public void symbolReplaceResolvesNumberedCaptureGroups() {
        assertEquals("ba", str(RP + "[Symbol.replace].call(/(a)(b)/, 'ab', '$2$1')"));
        assertEquals("$5", str(RP + "[Symbol.replace].call(/(a)(b)/, 'ab', '$5')"));
        assertEquals("$0", str(RP + "[Symbol.replace].call(/(a)(b)/, 'ab', '$0')"));
        assertEquals("a1", str(RP + "[Symbol.replace].call(/(a)(b)/, 'ab', '$11')"));
    }

    @Test
    public void symbolReplaceResolvesNamedCaptureGroups() {
        assertEquals("a", str(RP + "[Symbol.replace].call(/(?<x>a)b/, 'ab', '$<x>')"));
        assertEquals("", str(RP + "[Symbol.replace].call(/(?<x>a)(?<y>z)?b/, 'ab', '$<y>')"));
        assertEquals("$<x>", str(RP + "[Symbol.replace].call(/ab/, 'ab', '$<x>')"));
    }

    @Test
    public void symbolReplacePassesNamedGroupsToAFunctionReplacer() {
        assertEquals("5", str(RP + "[Symbol.replace].call(/(?<x>a)b/, 'ab', (...args) => String(args.length))"));
        assertEquals("a", str(RP + "[Symbol.replace].call(/(?<x>a)b/, 'ab', (...args) => args[args.length - 1].x)"));
        assertEquals("4", str(RP + "[Symbol.replace].call(/(a)b/, 'ab', (...args) => String(args.length))"));
    }

    @Test
    public void symbolReplaceAdvancesPastEmptyGlobalMatches() {
        assertEquals("-a-b-", str(RP + "[Symbol.replace].call(/(?:)/g, 'ab', '-')"));
        assertEquals(4, num(RP + "[Symbol.replace].call(/(?:)/gu, 'a\\u{1F600}', '-').split('-').length"));
    }

    @Test
    public void symbolMatchCollectsEveryGlobalMatch() {
        assertEquals("a,a", str(RP + "[Symbol.match].call(/a/g, 'aba').join(',')"));
        assertEquals(3, num(RP + "[Symbol.match].call(/(?:)/g, 'ab').length"));
        assertInstanceOf(JsNull.class, Interpreter.run(RP + "[Symbol.match].call(/z/g, 'abc')"));
        assertEquals("b", str(RP + "[Symbol.match].call(/b/, 'abc')[0]"));
    }

    @Test
    public void symbolSearchRestoresLastIndexAfterTheProbe() {
        assertEquals(1, num(RP + "[Symbol.search].call(/b/g, 'abc')"));
        assertEquals(5, num("const r = /b/g; r.lastIndex = 5; " + RP + "[Symbol.search].call(r, 'abc'); r.lastIndex"));
        assertEquals(-1, num(RP + "[Symbol.search].call(/z/, 'abc')"));
    }

    @Test
    public void symbolSplitHandlesEmptyInputAndUnmatchedRegions() {
        assertEquals(1, num(RP + "[Symbol.split].call(/z/, '').length"));
        assertEquals(0, num(RP + "[Symbol.split].call(/(?:)/, '').length"));
        assertEquals("a,b,c", str(RP + "[Symbol.split].call(/[0-9]/, 'a1b2c').join(',')"));
        assertEquals("a,1,b", str(RP + "[Symbol.split].call(/([0-9])/, 'a1b').join(',')"));
        assertEquals(0, num(RP + "[Symbol.split].call(/b/, 'abc', 0).length"));
        assertEquals("a,1", str(RP + "[Symbol.split].call(/([0-9])/, 'a1b', 2).join(',')"));
        assertEquals("abc", str(RP + "[Symbol.split].call(/z/, 'abc').join(',')"));
    }

    @Test
    public void symbolSplitOnAPlainObjectFailsOnItsFlags() {
        assertEquals("SyntaxError", str("let caught = 'none';" + " try { " + RP + "[Symbol.split].call({}, 'abc'); }"
                + " catch (e) { caught = e.constructor.name; } caught"));
        assertEquals("TypeError", str("let caught = 'none';" + " try { " + RP + "[Symbol.split].call(1, 'abc'); }"
                + " catch (e) { caught = e.constructor.name; } caught"));
    }

    @Test
    public void symbolMatchAllReturnsAnIterator() {
        assertEquals("function", str("typeof RegExp.prototype[Symbol.matchAll]"));
        assertEquals("[object RegExp String Iterator]", str("Object.prototype.toString.call('ab'.matchAll(/a/g))"));
        assertEquals("a,b", str("[...'a1b'.matchAll(/[a-z]/g)].map(m => m[0]).join(',')"));
        assertEquals("a", str("[...'ab'.matchAll(/(?<x>a)/g)][0].groups.x"));
        assertEquals("1", str("String([...'aa'.matchAll(/a/g)].length - 1)"));
    }

    @Test
    public void aPrimitiveArgumentNeverExposesItsWellKnownSymbol() {
        assertEquals("1", str("Object.defineProperty(Number.prototype, Symbol.match,"
                + " { get() { throw new Error('read'); } }); 'a1b'.match(1)[0]"));
    }

    @Test
    public void theSymbolMethodsAreNonEnumerableOnThePrototype() {
        assertFalse(bool("Object.getOwnPropertyDescriptor(RegExp.prototype, Symbol.match).enumerable"));
        assertFalse(bool("Object.getOwnPropertyDescriptor(RegExp.prototype, Symbol.matchAll).enumerable"));
    }
}
