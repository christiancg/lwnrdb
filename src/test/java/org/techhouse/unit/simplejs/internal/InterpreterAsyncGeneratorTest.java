package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsString;

public class InterpreterAsyncGeneratorTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // reads the accumulator array reference after the event loop has drained
    private static String joined(String source) {
        final var array = (JsArray) Interpreter.run(source);
        final var sb = new StringBuilder();
        for (var i = 0; i < array.length(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(JsCoercion.toStr(array.get(i)));
        }
        return sb.toString();
    }

    private static JsArray arr() {
        return (JsArray) Interpreter.run("let out = [];\nasync function* g() {}\nasync function main() { for await (const x of g()) out.push(x); out.push('end'); }\nmain();\nout\n");
    }

    // an async generator call is an object with next/return/throw methods
    @Test
    public void test_async_generator_is_object() {
        assertEquals("object", str("typeof (async function* () {})()"));
        assertEquals("function", str("typeof (async function* () {})().next"));
    }

    // for-await consumes an async generator to completion
    @Test
    public void test_for_await_consumes_async_generator() {
        final var source = """
                let out = [];
                async function* g() { yield 1; yield 2; yield 3; }
                async function main() { for await (const x of g()) out.push(x); }
                main();
                out
                """;
        assertEquals("1,2,3", joined(source));
    }

    // an async generator may await between yields
    @Test
    public void test_await_between_yields() {
        final var source = """
                let out = [];
                async function* g() { yield await Promise.resolve(10); yield 20; }
                async function main() { for await (const x of g()) out.push(x); }
                main();
                out
                """;
        assertEquals("10,20", joined(source));
    }

    // manual next() calls return promises of {value, done}
    @Test
    public void test_manual_next() {
        final var source = """
                let out = [];
                async function* g() { yield 'a'; yield 'b'; }
                const it = g();
                it.next()
                    .then(s => { out.push(s.value); out.push(String(s.done)); return it.next(); })
                    .then(s => { out.push(s.value); return it.next(); })
                    .then(s => { out.push(String(s.done)); });
                out
                """;
        assertEquals("a,false,b,true", joined(source));
    }

    // for-await awaits each element of a sync iterable of promises
    @Test
    public void test_for_await_over_promise_array() {
        final var source = """
                let out = [];
                async function main() {
                    for await (const x of [Promise.resolve(1), 2, Promise.resolve(3)]) out.push(x);
                }
                main();
                out
                """;
        assertEquals("1,2,3", joined(source));
    }

    // yield* delegates to another async generator
    @Test
    public void test_async_yield_star() {
        final var source = """
                let out = [];
                async function* inner() { yield 1; yield 2; }
                async function* outer() { yield* inner(); yield 3; }
                async function main() { for await (const x of outer()) out.push(x); }
                main();
                out
                """;
        assertEquals("1,2,3", joined(source));
    }

    // throw() injects into the generator body and is catchable
    @Test
    public void test_throw_into_async_generator() {
        final var source = """
                let out = [];
                async function* g() { try { yield 1; } catch (e) { out.push('caught:' + e); } }
                const it = g();
                it.next().then(() => it.throw('boom')).then(s => out.push('done:' + s.done));
                out
                """;
        assertEquals("caught:boom,done:true", joined(source));
    }

    // return() unwinds through finally and reports done
    @Test
    public void test_return_runs_finally() {
        final var source = """
                let out = [];
                async function* g() { try { yield 1; yield 2; } finally { out.push('cleanup'); } }
                const it = g();
                it.next().then(() => it.return('x')).then(s => out.push('r:' + s.value + ':' + s.done));
                out
                """;
        assertEquals("cleanup,r:x:true", joined(source));
    }

    // an async generator class method works
    @Test
    public void test_async_generator_class_method() {
        final var source = """
                let out = [];
                class C { async *gen() { yield 1; yield 2; } }
                async function main() { const c = new C(); for await (const x of c.gen()) out.push(x); }
                main();
                out
                """;
        assertEquals("1,2", joined(source));
    }

    // a rejected await inside an async generator surfaces as a rejected step
    @Test
    public void test_rejected_await_rejects_step() {
        final var source = """
                let out = [];
                async function* g() { yield await Promise.reject('bad'); }
                g().next().then(s => out.push('ok'), e => out.push('err:' + e));
                out
                """;
        assertEquals("err:bad", joined(source));
    }

    // for await outside an async function is a syntax error
    @Test
    public void test_for_await_outside_async_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("for await (const x of [1]) {}"));
    }

    // an empty async generator completes immediately
    @Test
    public void test_empty_async_generator() {
        assertEquals("end", ((JsString) arr().get(0)).getValue());
    }
}
