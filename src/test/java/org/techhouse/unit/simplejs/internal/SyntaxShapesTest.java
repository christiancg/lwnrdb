package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

/**
 * Grammar corners the parser has to keep apart: an optional chain's reach, the object-pattern property forms,
 * the contextual keywords that are still ordinary identifiers, and the token kinds that can follow `await`.
 */
public class SyntaxShapesTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("""
                const a = null;
                a?.b.c === undefined && a?.b() === undefined && a?.() === undefined
                """)).getValue();
    }

    @Test
    public void test_an_object_pattern_takes_nested_defaults_computed_keys_and_a_rest() {
        assertEquals("1|2|d", str("""
                const source = { a: { b: 1 }, c: 2, d: 3 };
                const { a: { b } = {}, ['c']: c, ...rest } = source;
                [b, c, Object.keys(rest).join()].join('|')
                """));
    }

    @Test
    public void test_an_object_pattern_takes_numeric_and_string_keys() {
        assertEquals("zerokay", str("const { 0: x, 'k': y } = { 0: 'zero', k: 'kay' }; x + y"));
    }

    // The contextual keywords are ordinary identifiers everywhere they are not doing their special job
    @Test
    public void test_contextual_keywords_are_ordinary_bindings() {
        assertEquals(21, num("""
                const of = 1;
                const from = 2;
                const as = 3;
                const get = 4;
                const set = 5;
                const target = 6;
                of + from + as + get + set + target
                """));
    }

    @Test
    public void test_a_contextual_keyword_can_head_an_arrow() {
        assertEquals(2, num("const f = of => of + 1; f(1)"));
        assertEquals("function", str("const g = async of => of; typeof g"));
    }

    // A nullish link short-circuits the rest of the chain, including a later call
    @Test
    public void test_a_nullish_optional_chain_short_circuits_the_whole_chain() {
        assertTrue(bool());
    }

    // Parenthesising ends the chain, so the call is an ordinary one on the extracted function
    @Test
    public void test_a_parenthesised_optional_link_ends_the_chain() {
        assertEquals("5:5", str("const o = { b: () => 5 }; [o?.b(), (o?.b)()].join(':')"));
    }

    @Test
    public void test_delete_reaches_through_an_optional_chain() {
        assertEquals("true:absent", str("""
                const o = { b: 1 };
                [delete o?.b, 'b' in o ? 'present' : 'absent'].join(':')
                """));
    }

    // `null` joins as the empty string, which is what pins that the element is there at all
    @Test
    public void test_await_accepts_a_literal_of_every_kind() {
        assertEquals("1,x,true,,a,t,1", str("""
                [
                    await 1,
                    await 'x',
                    await true,
                    await null,
                    await /a/.source,
                    await `t`,
                    await 1n
                ].join(',')
                """));
    }

    @Test
    public void test_await_accepts_a_bracketed_or_prefixed_operand() {
        assertEquals("2,1,2,number,-1", str("""
                [
                    await [1, 2].length,
                    await { a: 1 }.a,
                    await (1 + 1),
                    await typeof 1,
                    await -1
                ].join(',')
                """));
    }

    @Test
    public void test_await_accepts_a_keyword_headed_operand() {
        assertEquals("0,3,8,2", str("""
                let counter = 1;
                [
                    await new Date(0).getTime(),
                    await function () { return 3; }(),
                    await class { static v = 8; }.v,
                    await ++counter
                ].join(',')
                """));
    }
}
