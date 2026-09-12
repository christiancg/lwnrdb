package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.exceptions.ReferenceErrorException;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterUsingTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String joinArray(String source) {
        final var array = (JsArray) Interpreter.run(source);
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(((JsString) array.get(i)).getValue());
        }
        return sb.toString();
    }

    private static String dispose(String body) {
        return "{ [Symbol.dispose]: () => { " + body + " } }";
    }

    @Test
    public void test_disposes_at_block_exit() {
        assertEquals("body,d",
                str("let log=[]; { using r = " + dispose("log.push('d')") + "; log.push('body'); } log.join(',')"));
    }

    @Test
    public void test_disposes_in_reverse_order() {
        final var source = "let log=[]; { using a=" + dispose("log.push('a')") + "; using b=" + dispose("log.push('b')")
                + "; } log.join(',')";
        assertEquals("b,a", str(source));
    }

    @Test
    public void test_disposes_on_throw() {
        final var source = "let log=[]; try { { using r=" + dispose("log.push('d')")
                + "; throw new Error('x'); } } catch(e){ log.push('c:'+e.message) } log.join(',')";
        assertEquals("d,c:x", str(source));
    }

    @Test
    public void test_disposes_on_return() {
        final var source = "let log=[]; function f(){ { using r=" + dispose("log.push('d')")
                + "; return 'ret'; } } let v=f(); log.push(v); log.join(',')";
        assertEquals("d,ret", str(source));
    }

    @Test
    public void test_disposes_on_break() {
        final var source = "let log=[]; for (let i=0;i<1;i++) { using r=" + dispose("log.push('d')")
                + "; break; } log.join(',')";
        assertEquals("d", str(source));
    }

    @Test
    public void test_null_resource_is_noop() {
        assertEquals("ok", str("let log=[]; { using r = null; using u = undefined; log.push('ok'); } log.join(',')"));
    }

    @Test
    public void test_non_disposable_throws_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("{ using r = {}; }"));
    }

    @Test
    public void test_dispose_not_callable_throws_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("{ using r = { [Symbol.dispose]: 5 }; }"));
    }

    @Test
    public void test_dispose_error_propagates() {
        final var source = "let out=''; try { { using r={ [Symbol.dispose]: () => { throw new Error('boom') } }; } }"
                + " catch(e){ out = e.message } out";
        assertEquals("boom", str(source));
    }

    @Test
    public void test_suppressed_error_when_both_throw() {
        final var source = "let out=''; try { { using r={ [Symbol.dispose]: () => { throw new Error('E2') } };"
                + " throw new Error('E1'); } } catch(e){ out = e.name+':'+e.error.message+':'+e.suppressed.message } out";
        assertEquals("SuppressedError:E2:E1", str(source));
    }

    @Test
    public void test_multiple_dispose_errors_chain() {
        final var source = "let out=''; try { { using a={ [Symbol.dispose]: () => { throw new Error('E1') } };"
                + " using b={ [Symbol.dispose]: () => { throw new Error('E2') } }; } }"
                + " catch(e){ out = e.name+'/'+e.error.message+'/'+e.suppressed.message } out";
        assertEquals("SuppressedError/E1/E2", str(source));
    }

    @Test
    public void test_for_of_using_disposes_each_iteration() {
        final var source = "let log=[]; for (using r of [" + dispose("log.push('x')") + "," + dispose("log.push('y')")
                + "]) { log.push('i'); } log.join(',')";
        assertEquals("i,x,i,y", str(source));
    }

    @Test
    public void test_for_of_using_head_bound_name_is_tdz_during_source_evaluation() {
        final var source = "let x = { [Symbol.dispose](){} }; for (using x of [x]) {}";
        assertThrows(ReferenceErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_function_body_using_disposes() {
        final var source = "let log=[]; function f(){ using r=" + dispose("log.push('d')")
                + "; log.push('body'); } f(); log.join(',')";
        assertEquals("body,d", str(source));
    }

    @Test
    public void test_switch_using_disposes() {
        final var source = "let log=[]; switch(1){ case 1: { using r=" + dispose("log.push('d')")
                + "; log.push('c'); } break; } log.join(',')";
        assertEquals("c,d", str(source));
    }

    @Test
    public void test_module_top_level_using_disposes() {
        final var source = "let log=[]; using r=" + dispose("log.push('d')") + "; log.push('body'); log";
        assertEquals("body,d", joinArray(source));
    }

    @Test
    public void test_generator_return_runs_dispose() {
        final var source = """
                let log = [];
                function* g() { using r = { [Symbol.dispose]: () => { log.push('d'); } }; yield 1; yield 2; }
                let it = g();
                it.next();
                it.return();
                log.join(',')
                """;
        assertEquals("d", str(source));
    }

    @Test
    public void test_await_using_awaits_async_dispose() {
        final var source = "let log=[]; async function f(){ await using r={ [Symbol.asyncDispose]: () => { log.push('ad') } };"
                + " log.push('body'); } f(); log.join(',')";
        assertEquals("body,ad", str(source));
    }

    @Test
    public void test_await_using_falls_back_to_sync_dispose() {
        final var source = "let log=[]; async function f(){ await using r=" + dispose("log.push('sd')")
                + "; log.push('body'); } f(); log.join(',')";
        assertEquals("body,sd", str(source));
    }

    @Test
    public void test_await_using_top_level() {
        final var source = "let log=[]; await using r={ [Symbol.asyncDispose]: () => { log.push('d') } };"
                + " log.push('body'); log";
        assertEquals("body,d", joinArray(source));
    }

    @Test
    public void test_await_using_outside_async_throws() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("function f(){ await using r = " + dispose("") + "; } f()"));
    }

    @Test
    public void test_script_abort_skips_dispose() {
        final var log = new ArrayList<String>();
        final var host = new SimpleHostBindings(new JsonObject(), null, log::add, new ResourceLimits(-1, 50, -1));
        final var result = new SimpleJs()
                .run("{ using r = { [Symbol.dispose]: () => { console.log('d'); } }; while (true) {} }", host);
        assertTrue(result.isError());
        assertTrue(log.stream().noneMatch(line -> line.contains("d")));
    }

    @Test
    public void test_typeof_symbol() {
        assertEquals("symbol", str("typeof Symbol.dispose"));
        assertEquals("symbol", str("typeof Symbol('x')"));
    }

    @Test
    public void test_symbol_identity() {
        assertFalse(bool("Symbol('a') === Symbol('a')"));
        assertTrue(bool("Symbol.dispose === Symbol.dispose"));
    }

    @Test
    public void test_symbol_keyed_property() {
        assertEquals(42, num("let o={}; o[Symbol.dispose]=42; o[Symbol.dispose]"));
        assertEquals("1,2", str("let o={}; o[Symbol.dispose]=1; o['Symbol(Symbol.dispose)']=2;"
                + " o[Symbol.dispose]+','+o['Symbol(Symbol.dispose)']"));
    }

    @Test
    public void test_symbol_missing_key_undefined() {
        assertEquals("undefined", str("let o={}; typeof o[Symbol.dispose]"));
    }

    @Test
    public void test_symbol_key_update() {
        assertEquals(3, num("let o={}; o[Symbol.dispose]=1; o[Symbol.dispose]+=2; o[Symbol.dispose]"));
    }

    @Test
    public void test_suppressed_error_constructor() {
        final var source = "let e = new SuppressedError('x', 'y', 'm'); e.name+':'+e.message+':'+e.error+':'+e.suppressed";
        assertEquals("SuppressedError:m:x:y", str(source));
    }

    @Test
    public void test_symbol_to_string_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'' + Symbol('x')"));
    }

    @Test
    public void test_class_instance_field_disposable() {
        final var source = """
                let log = [];
                class R { [Symbol.dispose] = () => { log.push('d'); }; }
                { using r = new R(); log.push('body'); }
                log.join(',')
                """;
        assertEquals("body,d", str(source));
    }

    @Test
    public void test_using_disposable_stack() {
        final var source = """
                let log = [];
                { using s = new DisposableStack(); s.defer(() => log.push('d')); log.push('body'); }
                log.join(',')
                """;
        assertEquals("body,d", str(source));
    }

    @Test
    public void test_disposable_stack_prototype_has_dispose() {
        assertTrue(bool("Symbol.dispose in DisposableStack.prototype"));
        assertTrue(bool("new DisposableStack() instanceof DisposableStack"));
    }

    @Test
    public void test_await_using_async_disposable_stack() {
        final var source = """
                let log = [];
                async function run() {
                    await using s = new AsyncDisposableStack();
                    s.defer(() => log.push('d'));
                    log.push('body');
                }
                run();
                log
                """;
        assertEquals("body,d", joinArray(source));
    }
}
