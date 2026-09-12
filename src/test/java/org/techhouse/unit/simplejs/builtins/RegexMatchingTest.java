package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class RegexMatchingTest {
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
    public void test_exec_groups() {
        assertEquals("2024", str("/(\\d+)-(\\d+)/.exec('2024-01')[1]"));
        assertEquals("01", str("/(\\d+)-(\\d+)/.exec('2024-01')[2]"));
        assertEquals(0, num("/(\\d+)/.exec('12ab').index"));
    }

    @Test
    public void test_exec_named_groups() {
        assertEquals("2024", str("/(?<year>\\d+)-(?<month>\\d+)/.exec('2024-01').groups.year"));
        assertEquals("01", str("/(?<year>\\d+)-(?<month>\\d+)/.exec('2024-01').groups.month"));
    }

    @Test
    public void test_exec_no_match() {
        assertInstanceOf(JsNull.class, Interpreter.run("/z/.exec('abc')"));
    }

    @Test
    public void test_exec_global_advances() {
        final var source = """
                const re = /\\d/g;
                re.exec('a1b2');
                re.exec('a1b2').index
                """;
        assertEquals(3, num(source));
    }

    @Test
    public void test_sticky_exec() {
        assertEquals(0, num("/a/y.exec('a').index"));
        assertInstanceOf(JsNull.class, Interpreter.run("/a/y.exec('ba')"));
    }

    @Test
    public void test_optional_group_undefined() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("/(a)(b)?/.exec('a')[2]"));
    }

    @Test
    public void test_global_exec_no_match_resets() {
        final var source = """
                const re = /z/g;
                re.exec('abc');
                re.lastIndex
                """;
        assertEquals(0, num(source));
    }

    @Test
    public void test_call_returns_a_matching_pattern_unchanged() {
        assertTrue(bool("const re = /x/i; RegExp(re) === re"));
        assertTrue(bool("const re = /x/i; RegExp(re, undefined) === re"));
        assertFalse(bool("const re = /x/i; new RegExp(re) === re"));
        assertFalse(bool("const re = /x/i; RegExp(re, 'g') === re"));
        assertTrue(bool("const like = { constructor: RegExp, [Symbol.match]: true }; RegExp(like) === like"));
        assertFalse(bool("const like = { constructor: Object, [Symbol.match]: true, source: 'a', flags: '' };"
                + "RegExp(like) === like"));
    }

    @Test
    public void test_v_flag_matches() {
        assertTrue(bool("/[a-z]+/v.test('abc')"));
        assertEquals("v", str("/x/v.flags"));
    }

    @Test
    public void test_indices_for_numbered_groups() {
        assertEquals(1, num("/(b)/d.exec('abc').indices[0][0]"));
        assertEquals(2, num("/(b)/d.exec('abc').indices[0][1]"));
        assertEquals(1, num("/(b)/d.exec('abc').indices[1][0]"));
        assertTrue(bool("/b/d.exec('abc').indices.groups === undefined"));
        assertTrue(bool("/a/d.hasIndices"));
        assertFalse(bool("/a/.hasIndices"));
    }

    @Test
    public void test_indices_for_named_groups() {
        assertEquals(1, num("/(?<w>b)/d.exec('abc').indices.groups.w[0]"));
        assertEquals(2, num("/(?<w>b)/d.exec('abc').indices.groups.w[1]"));
    }

    @Test
    public void test_indices_for_non_participating_group() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("/b(z)?/d.exec('abc').indices[1]"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("/(?<w>z)?b/d.exec('abc').indices.groups.w"));
    }

    @Test
    public void test_no_indices_without_flag() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("/b/.exec('abc').indices"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("'abc'.match(/b/).indices"));
    }

    @Test
    public void test_indices_through_string_methods() {
        assertEquals(1, num("'abc'.match(/b/d).indices[0][0]"));
        assertEquals(1, num("[...'abc'.matchAll(/b/dg)][0].indices[0][0]"));
    }

    @Test
    public void test_exec_result_is_array() {
        assertTrue(bool("/a/.exec('a') instanceof Array"));
        assertEquals(1, num("/a/.exec('a').length"));
    }

    @Test
    public void test_reg_exp_exec_rejects_non_object_non_null_result() {
        assertThrows(org.techhouse.simplejs.exceptions.TypeErrorException.class, () -> Interpreter.run("""
                class R extends RegExp {
                  exec(s) { return 5; }
                }
                new R('a')[Symbol.match]('a');
                """));
    }

    @Test
    public void anOverriddenExecIsHonouredByTheWholeProtocol() {
        final var subclass = "class R extends RegExp { exec(s) { return null; } } const r = new R('a');";
        assertFalse(bool(subclass + " r.test('a')"));
        assertInstanceOf(JsNull.class, Interpreter.run(subclass + " 'a'.match(r)"));
        assertEquals(-1, num(subclass + " 'a'.search(r)"));
        assertEquals("a", str(subclass + " 'a'.replace(r, 'X')"));
    }

    @Test
    public void anOverriddenExecOnAPlainRegexpIsHonoured() {
        final var overridden = "const r = /a/; r.exec = () => ['zz'];";
        assertTrue(bool(overridden + " r.test('nope')"));
        assertEquals("zz", str(overridden + " 'nope'.match(r)[0]"));
    }

    @Test
    public void lastIndexIsAnOwnDataProperty() {
        assertEquals("lastIndex", str("Object.getOwnPropertyNames(/a/).join(',')"));
        assertTrue(bool("Object.getOwnPropertyDescriptor(/a/, 'lastIndex').writable"));
        assertFalse(bool("Object.getOwnPropertyDescriptor(/a/, 'lastIndex').enumerable"));
        assertFalse(bool("Object.getOwnPropertyDescriptor(/a/, 'lastIndex').configurable"));
        assertEquals(0, num("/a/.lastIndex"));
    }

    @Test
    public void lastIndexIsStoredUncoercedAndCoercedOnlyByExec() {
        assertEquals("1",
                str("const r = /a/g; r.lastIndex = '1'; typeof r.lastIndex === 'string' ? r.lastIndex : 'no'"));
        assertEquals(2, num("const r = /a/g; r.lastIndex = '1'; r.exec('aa'); r.lastIndex"));
    }

    @Test
    public void aRefusedLastIndexWriteThrows() {
        assertEquals("TypeError",
                str("let caught = 'none'; const r = /a/y;"
                        + " Object.defineProperty(r, 'lastIndex', { writable: false });"
                        + " try { r.test('b'); } catch (e) { caught = e.constructor.name; } caught"));
    }

    @Test
    public void lastIndexCannotBeRedefinedAsAnAccessor() {
        assertEquals("TypeError",
                str("let caught = 'none'; const r = /a/;"
                        + " try { Object.defineProperty(r, 'lastIndex', { get() { return 0; } }); }"
                        + " catch (e) { caught = e.constructor.name; } caught"));
    }

    @Test
    public void aMatchAllIteratorIsSelfIterableAndFinishes() {
        assertTrue(bool("const it = 'ab'.matchAll(/./g); it[Symbol.iterator]() === it"));
        assertTrue(bool("const it = 'a'.matchAll(/a/g); it.next(); it.next().done"));
    }

    @Test
    public void matchAllRejectsANonGlobalRegexp() {
        assertEquals("TypeError", str("let caught = 'none';"
                + " try { 'a'.matchAll(/a/); } catch (e) { caught = e.constructor.name; } caught"));
    }
}
