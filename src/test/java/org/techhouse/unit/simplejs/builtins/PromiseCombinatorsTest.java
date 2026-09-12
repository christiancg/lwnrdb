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

    @Test
    public void test_finally() {
        final var out = arr(
                "let out = []; Promise.resolve(1).finally(() => out.push('f')).then(v => out.push(v)); out");
        assertEquals("f", string(out));
        assertEquals(1, num(out, 1));
    }

    @Test
    public void test_all_fulfils() {
        final var source = """
                let out = [];
                Promise.all([Promise.resolve(1), Promise.resolve(2), 3]).then(a => out.push(a.join(',')));
                out
                """;
        assertEquals("1,2,3", string(arr(source)));
    }

    @Test
    public void test_all_rejects() {
        final var source = """
                let out = [];
                Promise.all([Promise.resolve(1), Promise.reject('bad')]).catch(e => out.push(e));
                out
                """;
        assertEquals("bad", string(arr(source)));
    }

    @Test
    public void test_all_empty() {
        assertEquals(0, num(arr("let out = []; Promise.all([]).then(a => out.push(a.length)); out"), 0));
    }

    @Test
    public void test_race() {
        final var source = """
                let out = [];
                Promise.race([Promise.resolve('first'), Promise.resolve('second')]).then(v => out.push(v));
                out
                """;
        assertEquals("first", string(arr(source)));
    }

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

    @Test
    public void test_all_settled_empty() {
        assertEquals(0, num(arr("let out = []; Promise.allSettled([]).then(a => out.push(a.length)); out"), 0));
    }

    @Test
    public void test_any_first_fulfilment() {
        final var source = """
                let out = [];
                Promise.any([Promise.reject('a'), Promise.resolve('b')]).then(v => out.push(v));
                out
                """;
        assertEquals("b", string(arr(source)));
    }

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

    @Test
    public void test_any_empty() {
        final var source = """
                let out = [];
                Promise.any([]).catch(e => out.push(e.name + ':' + e.errors.length));
                out
                """;
        assertEquals("AggregateError:0", string(arr(source)));
    }

    @Test
    public void test_all_accepts_set() {
        final var source = """
                let out = [];
                Promise.all(new Set([Promise.resolve(1), Promise.resolve(2)])).then(a => out.push(a.join(',')));
                out
                """;
        assertEquals("1,2", string(arr(source)));
    }

    @Test
    public void test_all_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.all({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    @Test
    public void test_race_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.race({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    @Test
    public void test_all_settled_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.allSettled({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

    @Test
    public void test_any_non_iterable_rejects() {
        final var source = """
                let out = [];
                Promise.any({}).catch(e => out.push('caught'));
                out
                """;
        assertEquals("caught", string(arr(source)));
    }

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

    @Test
    public void test_finally_callback_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve(1).finally(() => { throw 'boom'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("boom", string(arr(source)));
    }

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
