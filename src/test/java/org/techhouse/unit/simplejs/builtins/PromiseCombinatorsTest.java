package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class PromiseCombinatorsTest {
    private static JsArray arr(String source) {
        return (JsArray) Interpreter.run(source);
    }

    private static double num(JsArray array, int index) {
        return ((JsNumber) array.get(index)).getValue();
    }

    private static String string(JsArray array) {
        return ((JsString) array.get(0)).getValue();
    }

    // finally runs before the following then and passes the value through
    @Test
    public void test_finally() {
        final var out = arr(
                "let out = []; Promise.resolve(1).finally(() => out.push('f')).then(v => out.push(v)); out");
        assertEquals("f", string(out));
        assertEquals(1, num(out, 1));
    }

    // Promise.all resolves with all values, coercing non-promises
    @Test
    public void test_all_fulfils() {
        final var source = """
                let out = [];
                Promise.all([Promise.resolve(1), Promise.resolve(2), 3]).then(a => out.push(a.join(',')));
                out
                """;
        assertEquals("1,2,3", string(arr(source)));
    }

    // Promise.all rejects on the first rejection
    @Test
    public void test_all_rejects() {
        final var source = """
                let out = [];
                Promise.all([Promise.resolve(1), Promise.reject('bad')]).catch(e => out.push(e));
                out
                """;
        assertEquals("bad", string(arr(source)));
    }

    // Promise.all with an empty array resolves immediately with an empty array
    @Test
    public void test_all_empty() {
        assertEquals(0, num(arr("let out = []; Promise.all([]).then(a => out.push(a.length)); out"), 0));
    }

    // Promise.race settles with the first settled promise
    @Test
    public void test_race() {
        final var source = """
                let out = [];
                Promise.race([Promise.resolve('first'), Promise.resolve('second')]).then(v => out.push(v));
                out
                """;
        assertEquals("first", string(arr(source)));
    }

    // Promise.allSettled reports per-element status, preserving input order
    @Test
    public void test_all_settled_mixed() {
        final var source = """
                let out = [];
                Promise.allSettled([Promise.resolve(1), Promise.reject('e')]).then(a =>
                    out.push(a[0].status + ',' + a[0].value + ',' + a[1].status + ',' + a[1].reason));
                out
                """;
        assertEquals("fulfilled,1,rejected,e", string(arr(source)));
    }

    // Promise.allSettled with an empty array resolves immediately with an empty array
    @Test
    public void test_all_settled_empty() {
        assertEquals(0, num(arr("let out = []; Promise.allSettled([]).then(a => out.push(a.length)); out"), 0));
    }

    // Promise.any resolves with the first fulfilment even if an earlier element rejects
    @Test
    public void test_any_first_fulfilment() {
        final var source = """
                let out = [];
                Promise.any([Promise.reject('a'), Promise.resolve('b')]).then(v => out.push(v));
                out
                """;
        assertEquals("b", string(arr(source)));
    }

    // Promise.any rejects with an AggregateError holding the reasons in input order when all reject
    @Test
    public void test_any_all_reject() {
        final var source = """
                let out = [];
                Promise.any([Promise.reject('a'), Promise.reject('b')]).catch(e =>
                    out.push(e.name + ':' + e.errors.join(',')));
                out
                """;
        assertEquals("AggregateError:a,b", string(arr(source)));
    }

    // Promise.any with an empty array rejects immediately with an empty AggregateError
    @Test
    public void test_any_empty() {
        final var source = """
                let out = [];
                Promise.any([]).catch(e => out.push(e.name + ':' + e.errors.length));
                out
                """;
        assertEquals("AggregateError:0", string(arr(source)));
    }

    // the combinators accept any iterable, not just arrays (Set here)
    @Test
    public void test_all_accepts_set() {
        final var source = """
                let out = [];
                Promise.all(new Set([Promise.resolve(1), Promise.resolve(2)])).then(a => out.push(a.join(',')));
                out
                """;
        assertEquals("1,2", string(arr(source)));
    }

    // Promise.all on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_all_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.all({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.race on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_race_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.race({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.allSettled on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_all_settled_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.allSettled({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.any on a non-iterable settles rejected instead of throwing out of the native call
    @Test
    public void test_any_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.any({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    // Promise.all opens its argument through GetIterator rather than reading array storage directly
    @Test
    public void test_all_invokes_get_iterator_not_array_fast_path() {
        final var source = """
                let out = [];
                let calls = 0;
                let items = {
                    [Symbol.iterator]() {
                        calls++;
                        let i = 0;
                        return { next: () => i < 2 ? { value: i++, done: false } : { done: true } };
                    }
                };
                Promise.all(items).then(a => out.push(calls + ':' + a.join(',')));
                out
                """;
        assertEquals("1:0,1", string(arr(source)));
    }

    // PerformPromiseAll reads `resolve` off the constructor once and calls it for every element
    @Test
    public void test_all_looks_up_resolve_per_iteration() {
        final var source = """
                let out = [];
                let calls = 0;
                function Ctor(executor) { executor(v => out.push('resolved:' + calls), () => {}); }
                Ctor.resolve = v => { calls++; return Promise.resolve(v); };
                Promise.all.call(Ctor, [1, 2, 3]);
                out
                """;
        assertEquals("resolved:3", string(arr(source)));
    }

    // finally resolves the thenable its callback returns before passing the original value along
    @Test
    public void test_finally_awaits_thenable_returned_by_callback() {
        final var source = """
                let out = [];
                Promise.resolve('v')
                    .finally(() => ({ then(res) { out.push('late'); res(); } }))
                    .then(v => out.push(v));
                out
                """;
        final var out = arr(source);
        assertEquals("late", string(out));
        assertEquals("v", ((JsString) out.get(1)).getValue());
    }

    // a throwing finally callback rejects the derived promise instead of being swallowed
    @Test
    public void test_finally_callback_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve(1).finally(() => { throw 'boom'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("boom", string(arr(source)));
    }

    // Promise.allKeyed resolves with a null-prototype object keyed by the input's enumerable keys
    @Test
    public void test_all_keyed_resolves_to_keyed_object() {
        final var source = """
                let out = [];
                Promise.allKeyed({ a: Promise.resolve(1), b: 2 }).then(r =>
                    out.push(Object.keys(r).join(',') + '|' + r.a + ',' + r.b
                        + '|' + (Object.getPrototypeOf(r) === null)));
                out
                """;
        assertEquals("a,b|1,2|true", string(arr(source)));
    }

    // Promise.allSettledKeyed reports a per-key status object
    @Test
    public void test_all_settled_keyed_reports_status() {
        final var source = """
                let out = [];
                Promise.allSettledKeyed({ a: Promise.resolve(1), b: Promise.reject('e') }).then(r =>
                    out.push(r.a.status + ',' + r.a.value + '|' + r.b.status + ',' + r.b.reason));
                out
                """;
        assertEquals("fulfilled,1|rejected,e", string(arr(source)));
    }

    // a thenable's `then` is called from a microtask, so it runs after the current script finishes
    @Test
    public void test_thenable_then_is_called_from_a_microtask() {
        final var source = """
                let out = [];
                let thenable = { then(res) { out.push('then'); res(1); } };
                Promise.resolve().then(() => thenable).then(() => out.push('done'));
                out.push('sync');
                out
                """;
        assertEquals("sync", string(arr(source)));
    }
}
