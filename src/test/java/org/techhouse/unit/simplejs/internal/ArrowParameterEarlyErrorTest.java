package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ArrowParameterEarlyErrorTest {
    private static String str() {
        return ((JsString) Interpreter.run("""
                function tag(strings) { return strings[0]; }
                function* g() {
                    const rich = (
                        a = 1 + 2,
                        b = a ? -a : +a,
                        { c = [1, 2].map(x => x), d: { e = new Date(0) } = {} } = {},
                        [f = `t${a}`, ...spread] = [],
                        h = tag`x`,
                        i = (a, b),
                        j = { k: [...[1]] },
                        l = Math.max(1, 2),
                        m = j.k[0],
                        n = typeof a,
                        ...rest
                    ) => [a, b, c.length, e instanceof Date, f, h, i, j.k[0], l, m, n, rest.length].join(',');
                    yield rich();
                }
                g().next().value
                """)).getValue();
    }

    private static double num() {
        return ((JsNumber) Interpreter.run("""
                function* g() {
                    const f = (a = function* () { yield 5; }) => a().next().value;
                    yield f();
                }
                g().next().value
                """)).getValue();
    }

    private static void rejects(String source) {
        final var failure = assertThrows(SyntaxErrorException.class, () -> Interpreter.run(source), source);
        assertTrue(failure.getMessage().contains("Arrow parameters"), failure.getMessage());
    }

    @Test
    public void test_a_parameter_list_of_ordinary_expressions_parses_inside_a_generator() {
        assertEquals("3,-3,2,true,t3,x,-3,1,2,1,number,0", str());
    }

    @Test
    public void test_a_nested_generator_in_a_default_is_not_the_arrows_yield() {
        assertEquals(5, num());
    }

    @Test
    public void test_a_bare_yield_default_is_rejected() {
        rejects("function* g() { const f = (a = yield 1) => a; }");
    }

    @Test
    public void test_a_yield_inside_a_pattern_default_is_rejected() {
        rejects("function* g() { const f = ({ a = yield 1 } = {}) => a; }");
        rejects("function* g() { const f = ([a = yield 1] = []) => a; }");
        rejects("function* g() { const f = (...[a = yield 1]) => a; }");
    }

    @Test
    public void test_a_yield_inside_a_literal_default_is_rejected() {
        rejects("function* g() { const f = (a = [yield 1]) => a; }");
        rejects("function* g() { const f = (a = { k: yield 1 }) => a; }");
        rejects("function* g() { const f = (a = `${yield 1}`) => a; }");
    }

    @Test
    public void test_a_yield_inside_a_call_or_construction_default_is_rejected() {
        rejects("function* g() { const f = (a = h(yield 1)) => a; }");
        rejects("function* g() { const f = (a = new C(yield 1)) => a; }");
        rejects("function* g() { const f = (a = obj[yield 1]) => a; }");
    }

    @Test
    public void test_a_yield_inside_a_compound_expression_default_is_rejected() {
        rejects("function* g() { const f = (a = (1, yield 2)) => a; }");
        rejects("function* g() { const f = (a = (yield 1) ? 1 : 2) => a; }");
        rejects("function* g() { const f = (a = 1 + (yield 1)) => a; }");
        rejects("function* g() { const f = (a = (yield 1) || 2) => a; }");
    }

    @Test
    public void test_an_await_default_is_rejected_in_async_code() {
        rejects("async function h() { const f = (a = await 1) => a; }");
    }
}
