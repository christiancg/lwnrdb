package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
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

    // joins a returned array's string elements; used to observe disposal that runs after the last statement
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

    // the resource is disposed when the block exits, after the body runs
    @Test
    public void test_disposes_at_block_exit() {
        assertEquals("body,d",
                str("let log=[]; { using r = " + dispose("log.push('d')") + "; log.push('body'); } log.join(',')"));
    }

    // multiple resources dispose in reverse declaration order
    @Test
    public void test_disposes_in_reverse_order() {
        final var source = "let log=[]; { using a=" + dispose("log.push('a')") + "; using b=" + dispose("log.push('b')")
                + "; } log.join(',')";
        assertEquals("b,a", str(source));
    }

    // a thrown body still disposes before the error propagates
    @Test
    public void test_disposes_on_throw() {
        final var source = "let log=[]; try { { using r=" + dispose("log.push('d')")
                + "; throw new Error('x'); } } catch(e){ log.push('c:'+e.message) } log.join(',')";
        assertEquals("d,c:x", str(source));
    }

    // a return through a using block disposes before returning
    @Test
    public void test_disposes_on_return() {
        final var source = "let log=[]; function f(){ { using r=" + dispose("log.push('d')")
                + "; return 'ret'; } } let v=f(); log.push(v); log.join(',')";
        assertEquals("d,ret", str(source));
    }

    // a break out of a using-containing block disposes
    @Test
    public void test_disposes_on_break() {
        final var source = "let log=[]; for (let i=0;i<1;i++) { using r=" + dispose("log.push('d')")
                + "; break; } log.join(',')";
        assertEquals("d", str(source));
    }

    // using null/undefined is a no-op and does not throw
    @Test
    public void test_null_resource_is_noop() {
        assertEquals("ok", str("let log=[]; { using r = null; using u = undefined; log.push('ok'); } log.join(',')"));
    }

    // a resource without a dispose method throws a TypeError at declaration
    @Test
    public void test_non_disposable_throws_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("{ using r = {}; }"));
    }

    // a dispose method that is not callable throws a TypeError
    @Test
    public void test_dispose_not_callable_throws_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("{ using r = { [Symbol.dispose]: 5 }; }"));
    }

    // a dispose error propagates when the body completed normally
    @Test
    public void test_dispose_error_propagates() {
        final var source = "let out=''; try { { using r={ [Symbol.dispose]: () => { throw new Error('boom') } }; } }"
                + " catch(e){ out = e.message } out";
        assertEquals("boom", str(source));
    }

    // a body error and a dispose error aggregate into a SuppressedError
    @Test
    public void test_suppressed_error_when_both_throw() {
        final var source = "let out=''; try { { using r={ [Symbol.dispose]: () => { throw new Error('E2') } };"
                + " throw new Error('E1'); } } catch(e){ out = e.name+':'+e.error.message+':'+e.suppressed.message } out";
        assertEquals("SuppressedError:E2:E1", str(source));
    }

    // multiple dispose errors chain: the newest is the error, the accumulated is suppressed
    @Test
    public void test_multiple_dispose_errors_chain() {
        final var source = "let out=''; try { { using a={ [Symbol.dispose]: () => { throw new Error('E1') } };"
                + " using b={ [Symbol.dispose]: () => { throw new Error('E2') } }; } }"
                + " catch(e){ out = e.name+'/'+e.error.message+'/'+e.suppressed.message } out";
        assertEquals("SuppressedError/E1/E2", str(source));
    }

    // for-of with a using head disposes each element after its iteration
    @Test
    public void test_for_of_using_disposes_each_iteration() {
        final var source = "let log=[]; for (using r of [" + dispose("log.push('x')") + "," + dispose("log.push('y')")
                + "]) { log.push('i'); } log.join(',')";
        assertEquals("i,x,i,y", str(source));
    }

    // a using at function-body top level disposes when the function returns
    @Test
    public void test_function_body_using_disposes() {
        final var source = "let log=[]; function f(){ using r=" + dispose("log.push('d')")
                + "; log.push('body'); } f(); log.join(',')";
        assertEquals("body,d", str(source));
    }

    // a using in a switch case disposes when the switch exits
    @Test
    public void test_switch_using_disposes() {
        final var source = "let log=[]; switch(1){ case 1: using r=" + dispose("log.push('d')")
                + "; log.push('c'); break; } log.join(',')";
        assertEquals("c,d", str(source));
    }

    // a using at module top level disposes when the module finishes (observed after the run)
    @Test
    public void test_module_top_level_using_disposes() {
        final var source = "let log=[]; using r=" + dispose("log.push('d')") + "; log.push('body'); log";
        assertEquals("body,d", joinArray(source));
    }

    // a using inside a generator is disposed when the generator is early-returned
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

    // await using awaits the async dispose method
    @Test
    public void test_await_using_awaits_async_dispose() {
        final var source = "let log=[]; async function f(){ await using r={ [Symbol.asyncDispose]: () => { log.push('ad') } };"
                + " log.push('body'); } f(); log.join(',')";
        assertEquals("body,ad", str(source));
    }

    // await using falls back to Symbol.dispose when no asyncDispose is present
    @Test
    public void test_await_using_falls_back_to_sync_dispose() {
        final var source = "let log=[]; async function f(){ await using r=" + dispose("log.push('sd')")
                + "; log.push('body'); } f(); log.join(',')";
        assertEquals("body,sd", str(source));
    }

    // await using at module top level is valid (disposal observed after the run)
    @Test
    public void test_await_using_top_level() {
        final var source = "let log=[]; await using r={ [Symbol.asyncDispose]: () => { log.push('d') } };"
                + " log.push('body'); log";
        assertEquals("body,d", joinArray(source));
    }

    // await using in a sync function is a runtime SyntaxError
    @Test
    public void test_await_using_outside_async_throws() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("function f(){ await using r = " + dispose("") + "; } f()"));
    }

    // a wall-clock abort skips user disposers
    @Test
    public void test_script_abort_skips_dispose() {
        final var log = new ArrayList<String>();
        final var host = new SimpleHostBindings(new JsonObject(), null, log::add, new ResourceLimits(-1, 50, -1));
        final var result = new SimpleJs()
                .run("{ using r = { [Symbol.dispose]: () => { console.log('d'); } }; while (true) {} }", host);
        assertTrue(result.isError());
        assertTrue(log.stream().noneMatch(line -> line.contains("d")));
    }

    // typeof a symbol is 'symbol'
    @Test
    public void test_typeof_symbol() {
        assertEquals("symbol", str("typeof Symbol.dispose"));
        assertEquals("symbol", str("typeof Symbol('x')"));
    }

    // each Symbol() call produces a distinct value
    @Test
    public void test_symbol_identity() {
        assertFalse(bool("Symbol('a') === Symbol('a')"));
        assertTrue(bool("Symbol.dispose === Symbol.dispose"));
    }

    // symbol-keyed properties round-trip and do not collide with string keys
    @Test
    public void test_symbol_keyed_property() {
        assertEquals(42, num("let o={}; o[Symbol.dispose]=42; o[Symbol.dispose]"));
        assertEquals("1,2", str("let o={}; o[Symbol.dispose]=1; o['Symbol(Symbol.dispose)']=2;"
                + " o[Symbol.dispose]+','+o['Symbol(Symbol.dispose)']"));
    }

    // reading an absent symbol key yields undefined
    @Test
    public void test_symbol_missing_key_undefined() {
        assertEquals("undefined", str("let o={}; typeof o[Symbol.dispose]"));
    }

    // a compound-assignment update through a symbol key works
    @Test
    public void test_symbol_key_update() {
        assertEquals(3, num("let o={}; o[Symbol.dispose]=1; o[Symbol.dispose]+=2; o[Symbol.dispose]"));
    }

    // SuppressedError constructor exposes error/suppressed/message
    @Test
    public void test_suppressed_error_constructor() {
        final var source = "let e = new SuppressedError('x', 'y', 'm'); e.name+':'+e.message+':'+e.error+':'+e.suppressed";
        assertEquals("SuppressedError:m:x:y", str(source));
    }

    // coercing a symbol to a string throws a TypeError
    @Test
    public void test_symbol_to_string_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("'' + Symbol('x')"));
    }

    // a class instance with a Symbol.dispose field is disposable via using
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
}
