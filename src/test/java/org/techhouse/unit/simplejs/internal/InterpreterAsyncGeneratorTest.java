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
        return (JsArray) Interpreter.run(
                "let out = [];\nasync function* g() {}\nasync function main() { for await (const x of g()) out.push(x); out.push('end'); }\nmain();\nout\n");
    }

    @Test
    public void test_async_generator_is_object() {
        assertEquals("object", str("typeof (async function* () {})()"));
        assertEquals("function", str("typeof (async function* () {})().next"));
    }

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

    // Regression: `return <expr>;` in an async generator must Await the value; the fix lives in the
    // statement evaluator, where the AST still distinguishes it from a bare `return;`.
    @Test
    public void test_explicit_return_of_a_thenable_is_awaited() {
        final var source = """
                let out = [];
                async function* g() { return Promise.resolve('resolved-value'); }
                const it = g();
                it.next().then(r => out.push(r.value + ':' + r.done));
                out
                """;
        assertEquals("resolved-value:true", joined(source));
    }

    @Test
    public void test_bare_return_with_no_argument_settles_with_undefined() {
        final var source = """
                let out = [];
                async function* g() { yield 1; return; }
                async function main() { for await (const x of g()) out.push(x); out.push('done'); }
                main();
                out
                """;
        assertEquals("1,done", joined(source));
    }

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

    @Test
    public void test_top_level_for_await_consumes_promises() {
        final var source = """
                let out = [];
                for await (const x of [Promise.resolve(1), Promise.resolve(2)]) out.push(x);
                out
                """;
        assertEquals("1,2", joined(source));
    }

    @Test
    public void test_for_await_inside_plain_function_throws() {
        assertThrows(SyntaxErrorException.class,
                () -> Interpreter.run("function f() { for await (const x of [1]) {} } f()"));
    }

    @Test
    public void test_empty_async_generator() {
        assertEquals("end", ((JsString) arr().get(0)).getValue());
    }

    @Test
    public void test_async_generator_prototype_is_patchable() {
        assertEquals("object", str("async function* g() { yield 1; } typeof Object.getPrototypeOf(g())"));
        assertEquals("9", joined("""
                let out = [];
                async function* g() { yield 1; }
                const it = g();
                Object.getPrototypeOf(it).next = function() { return Promise.resolve({value: 9, done: false}); };
                it.next().then(r => out.push(r.value));
                out
                """));
        assertEquals("1", joined("""
                let out = [];
                async function* g() { yield 1; }
                g().next().then(r => out.push(r.value));
                out
                """));
    }

    @Test
    public void test_async_yield_star_ignores_sync_iterator_when_async_present() {
        final var source = """
                let out = [];
                const obj = {
                    get [Symbol.iterator]() { out.push('sync-read'); return undefined; },
                    [Symbol.asyncIterator]() {
                        let i = 0;
                        return { next() { return Promise.resolve(i < 2 ? {value: i++, done: false} : {done: true}); } };
                    }
                };
                async function* g() { yield* obj; }
                async function main() { for await (const v of g()) out.push(v); }
                main();
                out
                """;
        assertEquals("0,1", joined(source));
    }

    @Test
    public void test_async_yield_star_non_callable_async_iterator_throws() {
        final var source = """
                let out = [];
                const obj = { [Symbol.asyncIterator]: 0 };
                async function* g() { yield* obj; }
                async function main() {
                    try { for await (const v of g()) out.push(v); }
                    catch (e) { out.push(e.constructor.name); }
                }
                main();
                out
                """;
        assertEquals("TypeError", joined(source));
    }

    @Test
    public void test_async_yield_star_awaits_sync_iterator_values() {
        final var source = """
                let out = [];
                const obj = { *[Symbol.iterator]() { yield Promise.resolve('a'); yield 'b'; } };
                async function* g() { yield* obj; }
                async function main() { for await (const v of g()) out.push(v); }
                main();
                out
                """;
        assertEquals("a,b", joined(source));
    }

    @Test
    public void test_for_await_rejects_non_callable_async_iterator() {
        final var source = """
                let out = [];
                async function main() {
                    try { for await (const v of { [Symbol.asyncIterator]: 0 }) out.push(v); }
                    catch (e) { out.push(e.constructor.name); }
                }
                main();
                out
                """;
        assertEquals("TypeError", joined(source));
    }

    // Regression: the yield* return-completion await was gated on fromSync instead of async, so a real
    // async inner iterator without `return` leaked an unresolved thenable.
    @Test
    public void test_yield_star_return_without_inner_return_method_awaits_the_value() {
        final var source = """
                let out = [];
                async function* g() {
                    yield* {
                        [Symbol.asyncIterator]() {
                            return { next() { return Promise.resolve({ value: 1, done: false }); } };
                        }
                    };
                }
                async function main() {
                    const it = g();
                    await it.next();
                    const r = await it.return(Promise.resolve('done-value'));
                    out.push(r.value);
                }
                main();
                out
                """;
        assertEquals("done-value", joined(source));
    }

    @Test
    public void test_request_queue_preserves_order() {
        final var source = """
                let out = [];
                async function* g() { yield 'first'; yield 'second'; }
                const it = g();
                const a = it.next();
                const b = it.next();
                const c = it.next();
                c.then(s => out.push('c:' + s.value + ':' + s.done));
                b.then(s => out.push('b:' + s.value));
                a.then(s => out.push('a:' + s.value));
                out
                """;
        assertEquals("a:first,b:second,c:undefined:true", joined(source));
    }

    @Test
    public void test_reentrant_next_queues_instead_of_deadlocking() {
        final var source = """
                let out = [];
                let it;
                async function* g() {
                    it.next().then(s => out.push('inner:' + s.value));
                    yield 1;
                    yield 2;
                }
                it = g();
                it.next().then(s => out.push('outer:' + s.value));
                out
                """;
        assertEquals("outer:1,inner:2", joined(source));
    }

    @Test
    public void test_yielded_promise_is_awaited() {
        final var source = """
                let out = [];
                async function* g() { yield Promise.resolve('unwrapped'); }
                async function main() { for await (const v of g()) out.push(v); }
                main();
                out
                """;
        assertEquals("unwrapped", joined(source));
    }

    @Test
    public void test_rejected_yield_operand_throws_at_the_yield() {
        final var source = """
                let out = [];
                async function* g() {
                    try { yield Promise.reject('bad'); }
                    catch (e) { out.push('caught:' + e); }
                }
                async function main() { for await (const v of g()) out.push(v); }
                main();
                out
                """;
        assertEquals("caught:bad", joined(source));
    }

    @Test
    public void test_return_at_suspended_start_awaits_its_value() {
        final var source = """
                let out = [];
                async function* g() { yield 1; }
                g().return(Promise.resolve('done')).then(s => out.push(s.value + ':' + s.done));
                out
                """;
        assertEquals("done:true", joined(source));
    }

    // AsyncGeneratorAwaitReturn's PromiseResolve reads value.constructor even when value is already a
    // promise, so a poisoned accessor there must reject return()'s promise rather than be skipped.
    @Test
    public void test_return_at_suspended_start_propagates_a_broken_promise_constructor() {
        final var source = """
                let out = [];
                async function* g() { throw new Error('must not resume'); }
                let broken = Promise.resolve(42);
                Object.defineProperty(broken, 'constructor', { get() { throw new Error('broken promise'); } });
                g().return(broken).then(
                    () => out.push('resolved'),
                    e => out.push('rejected:' + e.message)
                );
                out
                """;
        assertEquals("rejected:broken promise", joined(source));
    }

    @Test
    public void test_return_at_suspended_yield_injects_the_broken_promise_error_at_the_yield() {
        final var source = """
                let out = [];
                async function* g() {
                    try { yield; } catch (e) { out.push('caught:' + e.message); }
                }
                let broken = Promise.resolve(42);
                Object.defineProperty(broken, 'constructor', { get() { throw new Error('broken promise'); } });
                async function main() {
                    let it = g();
                    await it.next();
                    await it.return(broken);
                }
                main();
                out
                """;
        assertEquals("caught:broken promise", joined(source));
    }

    @Test
    public void test_delegated_values_are_not_unwrapped() {
        final var source = """
                let out = [];
                const inner = Promise.resolve('inner');
                const source_ = {
                    [Symbol.asyncIterator]() { return this; },
                    next() { return { value: inner, done: false }; }
                };
                async function* g() { yield* source_; }
                g().next().then(s => out.push(String(s.value === inner)));
                out
                """;
        assertEquals("true", joined(source));
    }
}
