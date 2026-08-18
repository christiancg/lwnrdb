package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

// `await`, `async` and `of` are lexer keywords but grammar-contextual: they are identifiers wherever
// the current function context does not reserve them.
public class AwaitYieldContextTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str() {
        return ((JsString) Interpreter.run("async function await() { return 1; } typeof await")).getValue();
    }

    private static void rejectsParse(String source) {
        final var error = assertThrows(RuntimeException.class, () -> Interpreter.run(source));
        assertTrue(error instanceof SyntaxErrorException || error instanceof UnexpectedTokenException,
                "expected a parse rejection but got " + error);
    }

    // await is an ordinary identifier at the top level of a script and in a plain function
    @Test
    public void test_await_is_an_identifier_outside_async_code() {
        assertEquals(1, num("var await = 1; await"));
        assertEquals(1, num("function f(await) { return await; } f(1)"));
        assertEquals(1, num("function* g(await) { return await; } g(1).next(); 1"));
        // at the top level `await(x)` is still the operator, so the call form is tested inside a
        // function, where an AwaitExpression is not in the grammar at all
        assertEquals(1, num("function f() { var await = function() { return 1; }; return await(); } f()"));
        assertEquals("function", str());
    }

    // at the top level of a script `await x` is still the operator, which the host contract keeps
    @Test
    public void test_top_level_await_stays_the_operator() {
        assertEquals(5, num("await 5"));
    }

    // inside async code await is reserved, so it is neither a binding nor a reference
    @Test
    public void test_await_is_reserved_inside_async_code() {
        rejectsParse("async function f() { var await = 1; }");
        rejectsParse("async function f(await) {}");
        rejectsParse("async (await) => {}");
        rejectsParse("async function f(a = await 1) {}");
    }

    // a class static block reserves await, but a function or arrow body inside it does not
    @Test
    public void test_await_in_a_class_static_block() {
        rejectsParse("class C { static { await; } }");
        rejectsParse("class C { static { class await {} } }");
        assertEquals(1, num("var r = 0; class C { static { (() => { class await {} }); r = 1; } } r"));
        assertEquals(1, num("var r = 0; class C { static { (function await(await) {}); r = 1; } } r"));
    }

    // `of` is only the loop keyword in a for-of head; everywhere else it is an identifier
    @Test
    public void test_of_is_a_contextual_identifier() {
        assertEquals(1, num("var of = 1; of"));
        assertEquals(1, num("function f(of) { return of; } f(1)"));
        assertEquals(15, num("var t = 0; for (var x of [7, 8]) { t += x; } t"));
        assertEquals(1, num("var of = { a: 1 }; of.a"));
        assertEquals(1, num("var of = function() { return 1; }; of()"));
    }

    // `async` is contextual too: only a function or an arrow head makes it a modifier
    @Test
    public void test_async_is_a_contextual_identifier() {
        assertEquals(1, num("var async = 1; async"));
        assertEquals(1, num("var async = function() { return 1; }; async(0)"));
        assertEquals(1, num("var async = { x: 1 }; async.x"));
        assertEquals(7, num("var async; for ((async) of [7]) ; async"));
        // the for-of head carries a lookahead restriction on a bare `async`
        rejectsParse("var async; for (async of [7]) ;");
    }

    // the new parser contexts do not change tick() accounting: a script near the budget still aborts
    @Test
    public void test_instruction_budget_still_aborts() {
        final var host = new SimpleHostBindings(null, null, null, new ResourceLimits(2_000L, -1, 100));
        final var result = new SimpleJs().run("var of = 0; while (true) { of = of + 1; }", host);
        assertTrue(result.isError(), "a runaway script must abort");
        assertEquals("ScriptLimitError", result.getErrorName());
    }
}
