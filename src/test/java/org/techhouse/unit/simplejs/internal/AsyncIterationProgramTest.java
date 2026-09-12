package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;

public class AsyncIterationProgramTest {
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

    @Test
    public void test_from_async_over_an_array() {
        assertEquals("1,2", joined("let out = []; Array.fromAsync([1, 2]).then(a => out.push(a.join(','))); out"));
    }

    @Test
    public void test_from_async_awaits_elements() {
        assertEquals("1,2",
                joined("let out = []; Array.fromAsync([Promise.resolve(1), 2]).then(a => out.push(a.join(','))); out"));
    }

    @Test
    public void test_from_async_with_a_mapper() {
        assertEquals("2,4",
                joined("let out = []; Array.fromAsync([1, 2], x => x * 2).then(a => out.push(a.join(','))); out"));
    }

    @Test
    public void test_from_async_rejects_a_non_callable_mapper() {
        assertEquals("TypeError",
                joined("let out = []; Array.fromAsync([1], 5).catch(e => out.push(e.constructor.name)); out"));
    }

    @Test
    public void test_from_async_over_an_array_like() {
        assertEquals("a,b", joined("""
                let out = [];
                Array.fromAsync({ length: 2, 0: 'a', 1: 'b' }).then(a => out.push(a.join(',')));
                out
                """));
    }

    @Test
    public void test_from_async_awaits_array_like_elements() {
        assertEquals("z", joined("""
                let out = [];
                Array.fromAsync({ length: 1, 0: Promise.resolve('z') }).then(a => out.push(a.join(',')));
                out
                """));
    }

    @Test
    public void test_from_async_over_an_async_generator() {
        assertEquals("1,2", joined("""
                let out = [];
                async function* g() { yield 1; yield 2; }
                Array.fromAsync(g()).then(a => out.push(a.join(',')));
                out
                """));
    }

    @Test
    public void test_from_async_propagates_a_rejection() {
        assertEquals("r", joined("""
                let out = [];
                Array.fromAsync([Promise.reject(new Error('r'))]).catch(e => out.push(e.message));
                out
                """));
    }

    @Test
    public void test_from_async_rejects_a_non_callable_async_iterator() {
        assertEquals("TypeError", joined("""
                let out = [];
                Array.fromAsync({ [Symbol.asyncIterator]: 5 }).catch(e => out.push(e.constructor.name));
                out
                """));
    }

    @Test
    public void test_from_async_rejects_a_non_callable_sync_iterator() {
        assertEquals("TypeError", joined("""
                let out = [];
                Array.fromAsync({ [Symbol.iterator]: 5 }).catch(e => out.push(e.constructor.name));
                out
                """));
    }

    @Test
    public void test_from_async_over_a_primitive() {
        assertEquals("0", joined("let out = []; Array.fromAsync(1).then(a => out.push(a.length)); out"));
    }

    @Test
    public void test_from_async_with_a_constructor_receiver() {
        assertEquals("true", joined("""
                let out = [];
                function C(n) { this.n = n; }
                Array.fromAsync.call(C, { length: 1, 0: 'q' }).then(a => out.push(a instanceof C));
                out
                """));
    }

    @Test
    public void test_async_iterator_from_a_sync_iterable() {
        assertEquals("1,2", joined("""
                let out = [];
                async function main() { for await (const v of AsyncIterator.from([1, 2])) out.push(v); }
                main();
                out
                """));
    }

    @Test
    public void test_async_iterator_from_an_async_generator() {
        assertEquals("5", joined("""
                let out = [];
                async function* g() { yield 5; }
                async function main() { for await (const v of AsyncIterator.from(g())) out.push(v); }
                main();
                out
                """));
    }

    @Test
    public void test_async_iterator_is_not_constructable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new AsyncIterator()"));
    }

    @Test
    public void test_async_dispose_closes_the_iterator() {
        assertEquals("closed", joined("""
                let out = [];
                const it = {
                    async next() { return { done: false, value: 1 }; },
                    async return() { out.push('closed'); return { done: true }; },
                    [Symbol.asyncIterator]() { return this; }
                };
                async function main() { await AsyncIterator.prototype[Symbol.asyncDispose].call(it); }
                main();
                out
                """));
    }

    @Test
    public void test_async_dispose_without_a_return_method() {
        assertEquals("ok",
                joined("""
                        let out = [];
                        async function main() {
                            await AsyncIterator.prototype[Symbol.asyncDispose].call({ async next() { return { done: true }; } });
                            out.push('ok');
                        }
                        main();
                        out
                        """));
    }

    @Test
    public void test_async_dispose_rejects_a_non_callable_return() {
        assertEquals("TypeError", joined("""
                let out = [];
                async function main() {
                    try {
                        await AsyncIterator.prototype[Symbol.asyncDispose].call({ return: 5 });
                    } catch (e) {
                        out.push(e.constructor.name);
                    }
                }
                main();
                out
                """));
    }

    @Test
    public void test_await_using_over_an_async_generator() {
        assertEquals("body", joined("""
                let out = [];
                async function* g() { yield 1; }
                async function main() { await using it = g(); out.push('body'); }
                main();
                out
                """));
    }

    @Test
    public void test_for_await_over_a_sync_iterable_of_promises() {
        assertEquals("1,2", joined("""
                let out = [];
                async function main() { for await (const v of [Promise.resolve(1), 2]) out.push(v); }
                main();
                out
                """));
    }

    @Test
    public void test_async_helper_rejects_a_non_callable_callback() {
        assertEquals("TypeError", joined("""
                let out = [];
                async function* g() { yield 1; }
                async function main() {
                    try { await g().map(1).next(); } catch (e) { out.push(e.constructor.name); }
                }
                main();
                out
                """));
    }

    @Test
    public void test_async_take_rejects_a_negative_limit() {
        assertEquals("RangeError", joined("""
                let out = [];
                async function* g() { yield 1; }
                async function main() {
                    try { await g().take(-1).next(); } catch (e) { out.push(e.constructor.name); }
                }
                main();
                out
                """));
    }

    @Test
    public void test_async_every() {
        assertEquals("true", joined("""
                let out = [];
                async function* g() { yield 1; yield 2; }
                async function main() { out.push(await g().every(x => x > 0)); }
                main();
                out
                """));
    }

    @Test
    public void test_async_find_without_a_match() {
        assertEquals("undefined", joined("""
                let out = [];
                async function* g() { yield 1; }
                async function main() { out.push(String(await g().find(x => x > 5))); }
                main();
                out
                """));
    }

    @Test
    public void test_async_for_each() {
        assertEquals("1,2", joined("""
                let out = [];
                async function* g() { yield 1; yield 2; }
                async function main() { await g().forEach(v => out.push(v)); }
                main();
                out
                """));
    }

    @Test
    public void test_async_reduce_rejects_an_empty_source() {
        assertEquals("TypeError", joined("""
                let out = [];
                async function* g() {}
                async function main() {
                    try { await g().reduce((a, b) => a + b); } catch (e) { out.push(e.constructor.name); }
                }
                main();
                out
                """));
    }

    @Test
    public void test_async_generator_return() {
        assertEquals("true,5", joined("""
                let out = [];
                async function* g() { yield 1; }
                async function main() {
                    const it = g();
                    const result = await it.return(5);
                    out.push(result.done, result.value);
                }
                main();
                out
                """));
    }

    @Test
    public void test_async_generator_throw() {
        assertEquals("caught", joined("""
                let out = [];
                async function* g() { try { yield 1; } catch (e) { out.push('caught'); } }
                async function main() { const it = g(); await it.next(); await it.throw('e'); }
                main();
                out
                """));
    }

    @Test
    public void test_for_await_break_closes_the_generator() {
        assertEquals("1,closed", joined("""
                let out = [];
                async function* g() { try { yield 1; yield 2; } finally { out.push('closed'); } }
                async function main() { for await (const v of g()) { out.push(v); break; } }
                main();
                out
                """));
    }

    // Regression: `for await` over a sync iterable applies TWO chained awaits per step - the inner
    // AsyncFromSyncIteratorContinuation await and ForIn/OfBodyEvaluation's own Await(nextResult).
    @Test
    @Timeout(5)
    public void test_for_await_over_a_sync_iterable_needs_two_ticks_per_step() {
        assertEquals("pre,t0,t1,loop,t2,t3,post", joined("""
                let out = [];
                async function main() {
                    out.push('pre');
                    for await (const v of [Promise.resolve(9)]) { out.push('loop'); }
                    out.push('post');
                }
                Promise.resolve(0)
                    .then(() => out.push('t0'))
                    .then(() => out.push('t1'))
                    .then(() => out.push('t2'))
                    .then(() => out.push('t3'));
                main();
                out
                """));
    }

    // Regression: a synchronous throw out of AsyncFromSyncIteratorContinuation's PromiseResolve must
    // still route through the same two-tick chain rather than throwing synchronously.
    @Test
    @Timeout(5)
    public void test_for_await_over_a_sync_iterable_defers_a_poisoned_constructor_by_two_ticks() {
        assertEquals("start,t0,t1,caught", joined("""
                let out = [];
                async function f() {
                    var p = Promise.resolve(0);
                    Object.defineProperty(p, "constructor", { get() { throw new Error(); } });
                    out.push('start');
                    for await (var x of [p]);
                    out.push('never');
                }
                Promise.resolve(0).then(() => out.push('t0')).then(() => out.push('t1'));
                f().catch(() => out.push('caught'));
                out
                """));
    }

    // Regression (test262 for-await-of/ticks-with-sync-iter-resolved-promise-and-constructor-lookup.js):
    // both `constructor` reads happen synchronously, back to back, before either settlement queues a tick.
    @Test
    @Timeout(5)
    public void test_for_await_reads_both_constructors_before_any_tick_then_one_more_on_completion() {
        assertEquals("pre,constructor,constructor,tick1,tick2,loop,constructor,tick3,tick4,post", joined("""
                let out = [];
                async function main() {
                    const p = Promise.resolve(0);
                    out.push('pre');
                    for await (const v of [p]) { out.push('loop'); }
                    out.push('post');
                }
                Promise.resolve(0)
                    .then(() => out.push('tick1'))
                    .then(() => out.push('tick2'))
                    .then(() => out.push('tick3'))
                    .then(() => out.push('tick4'));
                Object.defineProperty(Promise.prototype, 'constructor', {
                    get() { out.push('constructor'); return Promise; },
                    configurable: true
                });
                main();
                out
                """));
    }
}
