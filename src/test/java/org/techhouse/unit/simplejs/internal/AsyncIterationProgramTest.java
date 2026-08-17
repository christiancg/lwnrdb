package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
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

    // Array.fromAsync collects a plain array
    @Test
    public void test_from_async_over_an_array() {
        assertEquals("1,2", joined("let out = []; Array.fromAsync([1, 2]).then(a => out.push(a.join(','))); out"));
    }

    // Array.fromAsync awaits each element of a sync iterable
    @Test
    public void test_from_async_awaits_elements() {
        assertEquals("1,2",
                joined("let out = []; Array.fromAsync([Promise.resolve(1), 2]).then(a => out.push(a.join(','))); out"));
    }

    // Array.fromAsync applies its mapper
    @Test
    public void test_from_async_with_a_mapper() {
        assertEquals("2,4",
                joined("let out = []; Array.fromAsync([1, 2], x => x * 2).then(a => out.push(a.join(','))); out"));
    }

    // A non-callable mapper rejects the returned promise
    @Test
    public void test_from_async_rejects_a_non_callable_mapper() {
        assertEquals("TypeError",
                joined("let out = []; Array.fromAsync([1], 5).catch(e => out.push(e.constructor.name)); out"));
    }

    // An array-like without an iterator is read through its length
    @Test
    public void test_from_async_over_an_array_like() {
        assertEquals("a,b", joined("""
                let out = [];
                Array.fromAsync({ length: 2, 0: 'a', 1: 'b' }).then(a => out.push(a.join(',')));
                out
                """));
    }

    // Elements of an array-like are awaited too
    @Test
    public void test_from_async_awaits_array_like_elements() {
        assertEquals("z", joined("""
                let out = [];
                Array.fromAsync({ length: 1, 0: Promise.resolve('z') }).then(a => out.push(a.join(',')));
                out
                """));
    }

    // An async generator is drained through its async iterator
    @Test
    public void test_from_async_over_an_async_generator() {
        assertEquals("1,2", joined("""
                let out = [];
                async function* g() { yield 1; yield 2; }
                Array.fromAsync(g()).then(a => out.push(a.join(',')));
                out
                """));
    }

    // A rejected element rejects the whole result
    @Test
    public void test_from_async_propagates_a_rejection() {
        assertEquals("r", joined("""
                let out = [];
                Array.fromAsync([Promise.reject(new Error('r'))]).catch(e => out.push(e.message));
                out
                """));
    }

    // A non-callable Symbol.asyncIterator rejects the returned promise
    @Test
    public void test_from_async_rejects_a_non_callable_async_iterator() {
        assertEquals("TypeError", joined("""
                let out = [];
                Array.fromAsync({ [Symbol.asyncIterator]: 5 }).catch(e => out.push(e.constructor.name));
                out
                """));
    }

    // A non-callable Symbol.iterator rejects the returned promise
    @Test
    public void test_from_async_rejects_a_non_callable_sync_iterator() {
        assertEquals("TypeError", joined("""
                let out = [];
                Array.fromAsync({ [Symbol.iterator]: 5 }).catch(e => out.push(e.constructor.name));
                out
                """));
    }

    // A primitive input yields an empty array through the array-like path
    @Test
    public void test_from_async_over_a_primitive() {
        assertEquals("0", joined("let out = []; Array.fromAsync(1).then(a => out.push(a.length)); out"));
    }

    // A constructor receiver is used to build the result
    @Test
    public void test_from_async_with_a_constructor_receiver() {
        assertEquals("true", joined("""
                let out = [];
                function C(n) { this.n = n; }
                Array.fromAsync.call(C, { length: 1, 0: 'q' }).then(a => out.push(a instanceof C));
                out
                """));
    }

    // AsyncIterator.from adapts a sync iterable
    @Test
    public void test_async_iterator_from_a_sync_iterable() {
        assertEquals("1,2", joined("""
                let out = [];
                async function main() { for await (const v of AsyncIterator.from([1, 2])) out.push(v); }
                main();
                out
                """));
    }

    // AsyncIterator.from passes an async iterator through
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

    // The abstract AsyncIterator constructor cannot be called
    @Test
    public void test_async_iterator_is_not_constructable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new AsyncIterator()"));
    }

    // The async dispose hook calls the iterator's return
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

    // An iterator without a return is disposed without error
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

    // A non-callable return makes async dispose reject with a TypeError
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

    // An await using declaration disposes the async generator at scope exit
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

    // for await over a sync iterable awaits each promise it yields
    @Test
    public void test_for_await_over_a_sync_iterable_of_promises() {
        assertEquals("1,2", joined("""
                let out = [];
                async function main() { for await (const v of [Promise.resolve(1), 2]) out.push(v); }
                main();
                out
                """));
    }

    // A non-callable async helper callback rejects with a TypeError
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

    // A negative async take limit rejects with a RangeError
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

    // every over an async generator reports the folded result
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

    // find over an async generator reports undefined when nothing matches
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

    // forEach visits every value of an async generator
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

    // reduce with no initial value over an empty async generator rejects with a TypeError
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

    // An async generator's return settles a done result
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

    // An async generator's throw is observable inside the body
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

    // Breaking out of for await runs the generator's finally block
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
}
