package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;

// AsyncIterator.prototype helpers, driven off async generators and consumed via for-await or await.
public class AsyncIteratorHelperTest {
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

    private static String await(String body) {
        return joined("let out = [];\nasync function* g() { yield 1; yield 2; yield 3; }\n" + "async function main() { "
                + body + " }\nmain();\nout\n");
    }

    // map transforms each yielded value
    @Test
    public void test_map_to_array() {
        assertEquals("10,20,30", await("out.push(...await g().map(x => x * 10).toArray());"));
    }

    // filter + take compose lazily
    @Test
    public void test_filter_take() {
        final var source = """
                let out = [];
                async function* g() { for (let i = 0; i < 10; i++) yield i; }
                async function main() { for await (const v of g().filter(x => x % 2 === 0).take(3)) out.push(v); }
                main();
                out
                """;
        assertEquals("0,2,4", joined(source));
    }

    // drop skips the leading elements
    @Test
    public void test_drop() {
        final var source = """
                let out = [];
                async function* g() { for (let i = 0; i < 5; i++) yield i; }
                async function main() { for await (const v of g().drop(2)) out.push(v); }
                main();
                out
                """;
        assertEquals("2,3,4", joined(source));
    }

    // flatMap flattens the mapped iterables
    @Test
    public void test_flat_map() {
        assertEquals("1,10,2,20,3,30", await("for await (const v of g().flatMap(x => [x, x * 10])) out.push(v);"));
    }

    // reduce folds to a single awaited value
    @Test
    public void test_reduce() {
        assertEquals("6", await("out.push(await g().reduce((a, b) => a + b, 0));"));
    }

    // reduce with no seed uses the first element
    @Test
    public void test_reduce_no_seed() {
        assertEquals("6", await("out.push(await g().reduce((a, b) => a + b));"));
    }

    // toArray collects everything
    @Test
    public void test_to_array() {
        assertEquals("1,2,3", await("out.push(...await g().toArray());"));
    }

    // forEach visits every element
    @Test
    public void test_for_each() {
        assertEquals("1,2,3", await("await g().forEach(x => out.push(x));"));
    }

    // some resolves true on the first match
    @Test
    public void test_some() {
        assertEquals("true", await("out.push(await g().some(x => x === 2));"));
    }

    // every resolves false on the first miss
    @Test
    public void test_every() {
        assertEquals("false", await("out.push(await g().every(x => x < 3));"));
    }

    // find resolves the first matching element
    @Test
    public void test_find() {
        assertEquals("2", await("out.push(await g().find(x => x >= 2));"));
    }

    // a promise-returning callback is awaited
    @Test
    public void test_map_awaits_callback() {
        assertEquals("101,102,103", await("out.push(...await g().map(x => Promise.resolve(x + 100)).toArray());"));
    }

    // AsyncIterator.from adapts a sync iterable
    @Test
    public void test_from_sync_iterable() {
        final var source = """
                let out = [];
                async function main() { \
                out.push(...await AsyncIterator.from([7, 8, 9]).map(x => x + 1).toArray()); }
                main();
                out
                """;
        assertEquals("8,9,10", joined(source));
    }

    // the abstract AsyncIterator constructor is not callable
    @Test
    public void test_abstract_constructor_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new AsyncIterator()"));
    }

    // Array.fromAsync drains an async iterable in order
    @Test
    public void test_from_async_async_iterable() {
        assertEquals("1,2,3", await("out.push(...await Array.fromAsync(g()));"));
    }

    // Array.fromAsync awaits each value of a sync iterable
    @Test
    public void test_from_async_awaits_sync_iterable_values() {
        final var source = """
                let out = [];
                async function main() {
                    out.push(...await Array.fromAsync([Promise.resolve('a'), 'b']));
                }
                main();
                out
                """;
        assertEquals("a,b", joined(source));
    }

    // Array.fromAsync applies mapfn with the element index and honours thisArg
    @Test
    public void test_from_async_mapfn_and_this_arg() {
        final var source = """
                let out = [];
                async function main() {
                    const scale = { factor: 10 };
                    out.push(...await Array.fromAsync([1, 2], function (v, i) {
                        return v * this.factor + i;
                    }, scale));
                }
                main();
                out
                """;
        assertEquals("10,21", joined(source));
    }

    // an input that is neither async- nor sync-iterable falls back to the array-like path
    @Test
    public void test_from_async_array_like_fallback() {
        final var source = """
                let out = [];
                async function main() {
                    out.push(...await Array.fromAsync({ length: 2, 0: Promise.resolve('x'), 1: 'y' }));
                }
                main();
                out
                """;
        assertEquals("x,y", joined(source));
    }

    // a rejecting mapfn rejects the returned promise and closes the source iterator
    @Test
    public void test_from_async_mapfn_rejection_closes_iterator() {
        final var source = """
                let out = [];
                const items = {
                    [Symbol.iterator]() {
                        return {
                            next: () => ({ value: 1, done: false }),
                            return() { out.push('closed'); return {}; }
                        };
                    }
                };
                async function main() {
                    try { await Array.fromAsync(items, () => { throw 'boom'; }); }
                    catch (e) { out.push(e); }
                }
                main();
                out
                """;
        assertEquals("closed,boom", joined(source));
    }

    // Array.fromAsync builds its result through the receiver when the receiver is a constructor
    @Test
    public void test_from_async_uses_this_constructor() {
        final var source = """
                let out = [];
                let built = 0;
                function Bag(n) { built++; this.length = 0; }
                async function main() {
                    const bag = await Array.fromAsync.call(Bag, [7, 8]);
                    out.push(built + ':' + bag.length + ':' + bag[0] + ',' + bag[1]);
                }
                main();
                out
                """;
        assertEquals("1:2:7,8", joined(source));
    }

    // helpers are lazy: take stops pulling once its budget is spent
    @Test
    public void test_take_is_lazy() {
        final var source = """
                let out = [];
                let pulled = 0;
                async function* g() { while (true) { pulled++; yield pulled; } }
                async function main() { for await (const v of g().take(2)) out.push(v); out.push('pulled=' + pulled); }
                main();
                out
                """;
        assertEquals("1,2,pulled=2", joined(source));
    }

    // AsyncIteratorClose's GetMethod rejects a present-but-non-callable `return`
    @Test
    public void test_for_await_close_rejects_non_callable_return() {
        final var source = """
                let out = [];
                const asyncIterable = {
                    [Symbol.asyncIterator]() {
                        return { next() { return { done: false, value: 1 }; }, return: 5 };
                    }
                };
                async function main() {
                    try {
                        for await (const x of asyncIterable) { break; }
                        out.push('no throw');
                    } catch (e) {
                        out.push(e.name);
                    }
                }
                main();
                out
                """;
        assertEquals("TypeError", joined(source));
    }
}
