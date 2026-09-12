package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

public class RegExpProtocolTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String attempt(String expression) {
        return str("(() => { try { return String(" + expression + "); } catch (e) { return e.constructor.name; } })()");
    }

    @Test
    public void test_to_string_reports_canonical_flag_order() {
        assertEquals("/a/gimsuy", str("/a/yusmig.toString()"));
        assertEquals("/a/", str("/a/.toString()"));
    }

    @Test
    public void test_an_overridden_exec_drives_match() {
        assertEquals("[\"x\"]:2", str("""
                const re = /x/g;
                let calls = 0;
                re.exec = function (s) {
                    calls++;
                    return calls === 1 ? Object.assign(['x'], { index: 1, input: s }) : null;
                };
                [JSON.stringify('axb'.match(re)), calls].join(':')
                """));
    }

    @Test
    public void test_an_exec_returning_a_primitive_is_refused() {
        assertEquals("TypeError", attempt("""
                (() => { const re = /y/; re.exec = () => 42; return 'y'.match(re); })()
                """));
    }

    @Test
    public void test_match_all_iterates_through_a_subclass_matcher() {
        assertEquals("2", str("class MyRe extends RegExp {} [...'aa'.matchAll(new MyRe('a', 'g'))].length + ''"));
    }

    @Test
    public void test_a_sticky_match_reads_and_advances_last_index() {
        assertEquals("true:2", str("""
                const sticky = /a/y;
                sticky.lastIndex = 1;
                [sticky.test('ba'), sticky.lastIndex].join(':')
                """));
    }

    @Test
    public void test_match_all_refuses_a_non_object_receiver() {
        assertEquals("TypeError", attempt("RegExp.prototype[Symbol.matchAll].call('nope', 'aa')"));
    }

    @Test
    public void test_escape_neutralises_pattern_syntax() {
        assertEquals("\\x61\\.b", str("RegExp.escape('a.b')"));
    }
}
