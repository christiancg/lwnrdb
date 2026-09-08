package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class RegexProgramTest {
    private static double num() {
        return ((JsNumber) Interpreter.run("'abc'.search({ [Symbol.search]() { return 42; } })")).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // exec reports the match, its index and the input
    @Test
    public void test_exec_result_shape() {
        assertEquals("1:ab", str("const m = /b/.exec('ab'); m.index + ':' + m.input"));
    }

    // A non-participating group is undefined in the result
    @Test
    public void test_exec_non_participating_group() {
        assertEquals("undefined:b", str("const m = /(a)|(b)/.exec('b'); String(m[1]) + ':' + m[2]"));
    }

    // The d flag adds match indices, including named ones
    @Test
    public void test_exec_indices() {
        assertEquals("1-2:1-2",
                str("const m = /(?<a>b)/d.exec('ab'); m.indices[0].join('-') + ':' + m.indices.groups.a.join('-')"));
    }

    // A global regex advances lastIndex between calls
    @Test
    public void test_global_exec_advances_last_index() {
        assertEquals("1:1", str("const r = /a/g; r.exec('aa'); r.lastIndex + ':' + r.exec('aa').index"));
    }

    // A sticky regex that fails at lastIndex resets it
    @Test
    public void test_sticky_miss_resets_last_index() {
        assertEquals("null:0", str("const r = /a/y; r.lastIndex = 1; String(r.exec('ab')) + ':' + r.lastIndex"));
    }

    // A global regex whose lastIndex is past the input resets it
    @Test
    public void test_global_miss_resets_last_index() {
        assertEquals("null:0", str("const r = /a/g; r.lastIndex = 5; String(r.exec('a')) + ':' + r.lastIndex"));
    }

    // test on a global regex walks the input and then starts over
    @Test
    public void test_global_test_walks_the_input() {
        final var source = """
                const r = /a/g;
                [r.test('aa'), r.test('aa'), r.test('aa')].join(',')
                """;
        assertEquals("true,true,false", str(source));
    }

    // Every flag has an accessor on the instance
    @Test
    public void test_flag_accessors() {
        final var source = """
                const r = /a/dgimsuy;
                [r.hasIndices, r.global, r.ignoreCase, r.multiline, r.dotAll, r.unicode, r.sticky].join(',')
                """;
        assertEquals("true,true,true,true,true,true,true", str(source));
    }

    // flags, source and the string form are derived from the literal
    @Test
    public void test_flags_source_and_string_form() {
        assertEquals("gi:a:/a/gi", str("/a/gi.flags + ':' + /a/gi.source + ':' + String(/a/gi)"));
    }

    // The v flag reports unicodeSets
    @Test
    public void test_unicode_sets_flag() {
        assertEquals("true:v", str("const r = /a/v; r.unicodeSets + ':' + r.flags"));
    }

    // RegExp.prototype answers the flag accessors with undefined and an empty source
    @Test
    public void test_prototype_accessors() {
        assertEquals("undefined:(?:)", str("String(RegExp.prototype.global) + ':' + RegExp.prototype.source"));
    }

    // The constructor copies pattern and flags from another regex
    @Test
    public void test_construct_from_a_regex() {
        assertEquals("g:a", str("const r = new RegExp(/a/g); r.flags + ':' + r.source"));
    }

    // Explicit flags override the copied ones
    @Test
    public void test_construct_overrides_flags() {
        assertEquals("i", str("new RegExp(/a/g, 'i').flags"));
    }

    // An unknown flag is a SyntaxError
    @Test
    public void test_unknown_flag_is_a_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('a', 'q')"));
    }

    // The u and v flags are mutually exclusive
    @Test
    public void test_u_and_v_flags_conflict() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('a', 'uv')"));
    }

    // An unparseable pattern is a SyntaxError
    @Test
    public void test_bad_pattern_is_a_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('(')"));
    }

    // RegExp.escape neutralises the pattern metacharacters
    @Test
    public void test_escape() {
        assertEquals("\\x61\\.b\\*c", str("RegExp.escape('a.b*c')"));
    }

    // A global match returns every matched substring
    @Test
    public void test_global_match() {
        assertEquals("X,X", str("'aXbX'.match(/X/g).join(',')"));
    }

    // A non-global match returns a single match result
    @Test
    public void test_non_global_match() {
        assertEquals("b:1", str("const m = 'ab'.match(/b/); m[0] + ':' + m.index"));
    }

    // matchAll walks every match with its captures
    @Test
    public void test_match_all() {
        assertEquals("1,2", str("[...'a1b2'.matchAll(/[a-z](\\d)/g)].map(m => m[1]).join(',')"));
    }

    // matchAll requires a global regex
    @Test
    public void test_match_all_requires_a_global_regex() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[...'a1'.matchAll(/[a-z](\\d)/)]"));
    }

    // search reports the match position, or -1
    @Test
    public void test_search() {
        assertEquals("1:-1", str("'abc'.search(/b/) + ':' + 'abc'.search(/z/)"));
    }

    // split cuts the input on every match
    @Test
    public void test_split() {
        assertEquals("a|b|c", str("'a1b2c'.split(/\\d/).join('|')"));
    }

    // split honours its limit
    @Test
    public void test_split_limit() {
        assertEquals("a|b", str("'a1b2c'.split(/\\d/, 2).join('|')"));
    }

    // An empty pattern splits between the characters, without a trailing empty string
    @Test
    public void test_split_on_an_empty_pattern() {
        assertEquals("a|b", str("'ab'.split(/(?:)/).join('|')"));
    }

    // The replacement tokens cover the match and its surroundings
    @Test
    public void test_replace_dollar_tokens() {
        assertEquals("a[b|a|c|$]c", str("'abc'.replace(/b/, '[$&|$`|$\\'|$$]')"));
    }

    // Numbered groups are substituted into the replacement
    @Test
    public void test_replace_numbered_groups() {
        assertEquals("ba", str("'ab'.replace(/(a)(b)/, '$2$1')"));
    }

    // A named group is substituted into the replacement
    @Test
    public void test_replace_named_group() {
        assertEquals("<a>b", str("'ab'.replace(/(?<first>a)/, '<$<first>>')"));
    }

    // An unknown group token is left verbatim
    @Test
    public void test_replace_unknown_group_token() {
        assertEquals("$9b", str("'ab'.replace(/a/, '$9')"));
    }

    // A function replacer receives the match, captures, index and input
    @Test
    public void test_replace_with_a_function() {
        final var source = """
                'a1'.replace(/([a-z])(\\d)/,
                        (match, one, two, index, input) => [match, one, two, index, input].join('|'))
                """;
        assertEquals("a1|a|1|0|a1", str(source));
    }

    // A global replacement visits every match
    @Test
    public void test_replace_all_with_a_global_regex() {
        assertEquals("a-a-a", str("'aXaXa'.replaceAll(/X/g, '-')"));
    }

    // replaceAll requires a global regex
    @Test
    public void test_replace_all_requires_a_global_regex() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'aXa'.replaceAll(/X/, '-')"));
    }

    // A global empty match walks every position
    @Test
    public void test_replace_empty_matches() {
        assertEquals("-a-b-c-", str("'abc'.replace(/(?:)/g, '-')"));
    }

    // A string search value replaces only the first occurrence
    @Test
    public void test_replace_with_a_string_pattern() {
        assertEquals("a+b", str("'a-b'.replace('-', '+')"));
    }

    // replaceAll with a string search value replaces every occurrence
    @Test
    public void test_replace_all_with_a_string_pattern() {
        assertEquals("a+b+c", str("'a-b-c'.replaceAll('-', '+')"));
    }

    // A plain object with Symbol.replace takes over replace
    @Test
    public void test_symbol_replace_hook() {
        final var source = """
                const hook = { [Symbol.replace](target, replacement) { return 'custom:' + target + ':' + replacement; } };
                'abc'.replace(hook, 'x')
                """;
        assertEquals("custom:abc:x", str(source));
    }

    // A plain object with Symbol.split takes over split
    @Test
    public void test_symbol_split_hook() {
        assertEquals("x|y", str("'abc'.split({ [Symbol.split]() { return ['x', 'y']; } }).join('|')"));
    }

    // A plain object with Symbol.search takes over search
    @Test
    public void test_symbol_search_hook() {
        assertEquals(42, num());
    }

    // A plain object with Symbol.match takes over match
    @Test
    public void test_symbol_match_hook() {
        assertEquals("m:abc", str("'abc'.match({ [Symbol.match](target) { return 'm:' + target; } })"));
    }

    // The regex symbol methods can be invoked directly
    @Test
    public void test_symbol_methods_on_a_regex() {
        assertEquals("Xbc:b:a|c:1", str("""
                /a/[Symbol.replace]('abc', 'X') + ':' + /b/[Symbol.match]('abc')[0] + ':'
                        + /b/[Symbol.split]('abc').join('|') + ':' + /b/[Symbol.search]('abc')
                """));
    }

    // A numbered backreference must match the same text
    @Test
    public void test_numbered_backreference() {
        assertEquals("true:false", str("String(/(a)\\1/.test('aa')) + ':' + String(/(a)\\1/.test('ab'))"));
    }

    // A named backreference must match the same text
    @Test
    public void test_named_backreference() {
        assertTrue(bool("/(?<x>a)\\k<x>/.test('aa')"));
    }

    // A duplicate group name resolves to the alternative that participated
    @Test
    public void test_duplicate_named_group() {
        assertEquals("b", str("/(?:(?<x>a)|(?<x>b))/.exec('b').groups.x"));
    }

    // A general-category property escape works under the u flag
    @Test
    public void test_general_category_property_escape() {
        assertEquals("true:false", str("String(/\\p{L}/u.test('a')) + ':' + String(/\\p{L}/u.test('1'))"));
    }

    // A script property escape works under the u flag
    @Test
    public void test_script_property_escape() {
        assertTrue(bool("/\\p{Script=Greek}/u.test('\\u03b1')"));
    }

    // A supported binary property escape works under the u flag
    @Test
    public void test_binary_property_escape() {
        assertTrue(bool("/\\p{ASCII_Hex_Digit}/u.test('f')"));
    }

    // The Any property matches every code point
    @Test
    public void test_any_property_escape() {
        assertTrue(bool("/\\p{Any}/u.test('x')"));
    }

    // An unsupported property name is a SyntaxError
    @Test
    public void test_unsupported_property_escape() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("new RegExp('\\\\p{Emoji}', 'u')"));
    }

    // The v flag supports set subtraction
    @Test
    public void test_v_flag_subtraction() {
        assertEquals("true:false",
                str("String(/[\\p{L}--[a]]/v.test('b')) + ':' + String(/[\\p{L}--[a]]/v.test('a'))"));
    }

    // The v flag supports set intersection
    @Test
    public void test_v_flag_intersection() {
        assertTrue(bool("/[\\p{L}&&[a-z]]/v.test('a')"));
    }

    // The v flag supports nested classes
    @Test
    public void test_v_flag_nested_class() {
        assertTrue(bool("/[[a][b]]/v.test('b')"));
    }

    // The v flag supports single-character string literals
    @Test
    public void test_v_flag_string_literal() {
        assertTrue(bool("/[\\q{a}]/v.test('a')"));
    }

    // A ClassStringDisjunction may hold multi-code-point strings, matched longest-first
    @Test
    public void test_v_flag_multi_character_string_literal() {
        assertTrue(bool("new RegExp('[\\\\q{abc}]', 'v').test('abc')"));
        assertFalse(bool("new RegExp('[\\\\q{abc}]', 'v').test('ab')"));
        assertEquals("abc", str("'xabcx'.match(new RegExp('[\\\\q{a|abc}]', 'v'))[0]"));
    }

    // The s flag lets the dot match a line terminator
    @Test
    public void test_dot_all_flag() {
        assertEquals("true:false", str("String(/a.b/s.test('a\\nb')) + ':' + String(/a.b/.test('a\\nb'))"));
    }

    // The m flag anchors at every line start
    @Test
    public void test_multiline_flag() {
        assertTrue(bool("/^b/m.test('a\\nb')"));
    }

    // The i flag ignores case
    @Test
    public void test_ignore_case_flag() {
        assertTrue(bool("/A/i.test('a')"));
    }

    // Lookbehind assertions are supported
    @Test
    public void test_lookbehind() {
        assertTrue(bool("/(?<=a)b/.test('ab')"));
    }

    // exec brand-checks its receiver
    @Test
    public void test_exec_requires_a_regex_receiver() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("RegExp.prototype.exec.call({}, 'a')"));
    }

    // test brand-checks its receiver
    @Test
    public void test_test_requires_a_regex_receiver() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("RegExp.prototype.test.call({}, 'a')"));
    }

    // toString brand-checks its receiver
    @Test
    public void test_to_string_requires_a_regex_receiver() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("RegExp.prototype.toString.call({})"));
    }

    // The source accessor brand-checks its receiver
    @Test
    public void test_source_accessor_requires_a_regex_receiver() {
        final var source = """
                Object.getOwnPropertyDescriptor(RegExp.prototype, 'source').get.call({})
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }
}
