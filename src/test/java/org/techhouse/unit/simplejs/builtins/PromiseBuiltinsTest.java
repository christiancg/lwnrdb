package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class PromiseBuiltinsTest {
    private static JsArray arr(String source) {
        return (JsArray) Interpreter.run(source);
    }

    private static double num(JsArray array, int index) {
        return ((JsNumber) array.get(index)).getValue();
    }

    private static String string(JsArray array) {
        return ((JsString) array.get(0)).getValue();
    }

    // the AggregateError global constructor stores the message and an errors array
    @Test
    public void test_aggregate_error_constructor() {
        final var source = "let e = new AggregateError(['x', 'y'], 'oops');"
                + " e.name + '|' + e.message + '|' + e.errors.join(',')";
        assertEquals("AggregateError|oops|x,y", ((JsString) Interpreter.run(source)).getValue());
    }

    // Promise.resolve settles fulfilled
    @Test
    public void test_resolve() {
        assertEquals(5, num(arr("let out = []; Promise.resolve(5).then(v => out.push(v)); out"), 0));
    }

    // Promise.reject is caught by catch
    @Test
    public void test_reject_and_catch() {
        assertEquals("c:e", string(arr("let out = []; Promise.reject('e').catch(v => out.push('c:' + v)); out")));
    }

    // then handlers chain, each transforming the resolved value
    @Test
    public void test_then_chaining() {
        assertEquals(2, num(arr("let out = []; Promise.resolve(1).then(v => v + 1).then(v => out.push(v)); out"), 0));
    }

    // finally runs before the following then and passes the value through
    @Test
    public void test_finally() {
        final var out = arr(
                "let out = []; Promise.resolve(1).finally(() => out.push('f')).then(v => out.push(v)); out");
        assertEquals("f", string(out));
        assertEquals(1, num(out, 1));
    }

    // a throwing then handler rejects the derived promise
    @Test
    public void test_then_handler_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve(1).then(() => { throw 'oops'; }).catch(e => out.push('c:' + e));
                out
                """;
        assertEquals("c:oops", string(arr(source)));
    }

    // new Promise runs the executor and resolves via its resolve callback
    @Test
    public void test_new_promise_resolve() {
        assertEquals(9, num(arr("let out = []; new Promise((res, rej) => res(9)).then(v => out.push(v)); out"), 0));
    }

    // new Promise rejects via its reject callback
    @Test
    public void test_new_promise_reject() {
        assertEquals("x",
                string(arr("let out = []; new Promise((res, rej) => rej('x')).catch(v => out.push(v)); out")));
    }

    // a throwing executor rejects the promise
    @Test
    public void test_new_promise_executor_throw_rejects() {
        final var source = """
                let out = [];
                new Promise((res, rej) => { throw 'bad'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("bad", string(arr(source)));
    }

    // a non-function resolver throws a TypeError
    @Test
    public void test_new_promise_bad_resolver_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Promise(5)"));
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

    // Promise.withResolvers exposes the promise and its resolve function; resolving settles it
    @Test
    public void test_with_resolvers_resolve() {
        final var source = """
                let out = [];
                let { promise, resolve } = Promise.withResolvers();
                promise.then(v => out.push(v));
                resolve(7);
                out
                """;
        assertEquals(7, num(arr(source), 0));
    }

    // Promise.withResolvers exposes a reject function that rejects the promise
    @Test
    public void test_with_resolvers_reject() {
        final var source = """
                let out = [];
                let { promise, reject } = Promise.withResolvers();
                promise.catch(e => out.push(e));
                reject('boom');
                out
                """;
        assertEquals("boom", string(arr(source)));
    }

    // Promise.try runs the callback and fulfils with its return value, passing extra args
    @Test
    public void test_try_fulfils() {
        final var source = """
                let out = [];
                Promise.try((a, b) => a + b, 2, 3).then(v => out.push(v));
                out
                """;
        assertEquals(5, num(arr(source), 0));
    }

    // Promise.try turns a synchronous throw into a rejection
    @Test
    public void test_try_rejects_on_throw() {
        final var source = """
                let out = [];
                Promise.try(() => { throw 'nope'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("nope", string(arr(source)));
    }

    // Promise.try adopts a promise returned by the callback
    @Test
    public void test_try_adopts_promise() {
        final var source = """
                let out = [];
                Promise.try(() => Promise.resolve(11)).then(v => out.push(v));
                out
                """;
        assertEquals(11, num(arr(source), 0));
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

    // Promise.resolve assimilates a user thenable instead of fulfilling with the thenable itself
    @Test
    public void test_resolve_assimilates_thenable() {
        final var source = """
                let out = [];
                Promise.resolve({ then(res) { res(42); } }).then(v => out.push(v));
                out
                """;
        assertEquals(42, num(arr(source), 0));
    }

    // a thenable whose then throws synchronously rejects the derived promise
    @Test
    public void test_thenable_sync_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve({ then() { throw 'boom'; } }).catch(e => out.push(e));
                out
                """;
        assertEquals("boom", string(arr(source)));
    }

    // a thenable that never invokes its resolve/reject callback leaves the promise pending forever
    @Test
    public void test_thenable_never_settling_stays_pending() {
        final var source = """
                let out = [];
                Promise.resolve({ then() {} }).then(v => out.push(v), e => out.push(e));
                out
                """;
        assertEquals(0, arr(source).length());
    }

    // `new` on a Promise subclass runs the subclass constructor with the base executor; then() then
    // builds its result through the same constructor, so it runs a second time
    @Test
    public void test_subclass_constructor_is_honoured() {
        final var source = """
                let out = [];
                let seen = 0;
                class Sub extends Promise {
                    constructor(executor) { super(executor); seen++; }
                }
                new Sub(res => res(7)).then(v => out.push(seen + ':' + v));
                out
                """;
        assertEquals("2:7", string(arr(source)));
    }

    // then() builds its result through SpeciesConstructor, so a subclass receiver yields a subclass
    @Test
    public void test_then_uses_species_constructor() {
        final var source = """
                let out = [];
                class Sub extends Promise {
                    static get [Symbol.species]() { return Sub; }
                }
                let derived = new Sub(res => res(1)).then(v => v + 1);
                derived.then(v => out.push((derived instanceof Sub) + ':' + v));
                out
                """;
        assertEquals("true:2", string(arr(source)));
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

    // a combinator called on a foreign constructor settles through that constructor's own executor
    @Test
    public void test_combinators_use_receiver_capability() {
        final var source = """
                let out = [];
                let executors = 0;
                function Ctor(executor) { executors++; executor(v => out.push('r:' + executors), () => {}); }
                Ctor.resolve = v => Promise.resolve(v);
                Promise.all.call(Ctor, []);
                out
                """;
        assertEquals("r:1", string(arr(source)));
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

    // resolving a promise with itself rejects it with a TypeError instead of hanging
    @Test
    public void test_self_resolution_rejects() {
        final var source = """
                let out = [];
                let resolve;
                let p = new Promise(r => { resolve = r; });
                p.catch(e => out.push(e.name));
                resolve(p);
                out
                """;
        assertEquals("TypeError", string(arr(source)));
    }

    // resolving with a promise queues the spec's thenable job, so the value arrives two ticks later
    @Test
    public void test_resolving_with_a_promise_queues_a_thenable_job() {
        final var source = """
                let out = [];
                new Promise(res => res(Promise.resolve('inner'))).then(v => out.push(v));
                Promise.resolve().then(() => out.push('b')).then(() => out.push('c'));
                out
                """;
        assertEquals("b", string(arr(source)));
    }

    // a subclass constructor's super(executor) initialises the wrapped promise exactly once, and the
    // instance behaves as a promise
    @Test
    public void test_subclass_super_initialises_the_wrapped_promise() {
        final var source = """
                let out = [];
                let calls = 0;
                class P extends Promise { constructor(e) { super(e); } }
                let p = new P(res => { calls++; res('v'); });
                p.then(v => out.push(calls + '|' + v + '|' + (p instanceof P) + ',' + (p instanceof Promise)));
                out
                """;
        assertEquals("1|v|true,true", string(arr(source)));
    }

    // a subclass inherits the statics of its native superclass, and they build instances of it
    @Test
    public void test_subclass_inherits_native_statics() {
        final var source = """
                let out = [];
                class P extends Promise { constructor(e) { super(e); } }
                let p = P.resolve('v');
                p.then(v => out.push(v + '|' + (p instanceof P)));
                out
                """;
        assertEquals("v|true", string(arr(source)));
    }

    // a subclass with no explicit Symbol.species inherits %Promise%'s, which selects the subclass
    @Test
    public void test_then_uses_the_inherited_species_constructor() {
        final var source = """
                let out = [];
                class P extends Promise { constructor(e) { super(e); } }
                let q = P.resolve(1).then(v => v);
                q.then(() => out.push(String(q instanceof P)));
                out
                """;
        assertEquals("true", string(arr(source)));
    }

    // NewPromiseCapability rejects a `this` that is not a constructor
    @Test
    public void test_combinator_on_a_non_constructor_this_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Promise.all.call(Math.max, [])"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Promise.resolve.call(Math.max, 1)"));
    }

    // PerformPromiseAll reads Get(C, "resolve") once, then calls it per element
    @Test
    public void test_combinator_reads_resolve_once() {
        final var source = """
                let out = [];
                let gets = 0;
                let calls = 0;
                let original = Promise.resolve;
                Object.defineProperty(Promise, 'resolve', {
                    configurable: true,
                    get() {
                        gets++;
                        return function (v) { calls++; return original.call(Promise, v); };
                    }
                });
                Promise.all([1, 2, 3]);
                out.push(gets + '|' + calls);
                out
                """;
        assertEquals("1|3", string(arr(source)));
    }

    // the per-element resolve functions are ordinary extensible functions of length 1, called once
    @Test
    public void test_resolve_element_functions_are_single_shot() {
        final var source = """
                let out = [];
                let element;
                let thenable = { then(fulfil) { element = fulfil; } };
                function NotPromise(executor) { executor(function () {}, function () {}); }
                NotPromise.resolve = function (v) { return v; };
                Promise.all.call(NotPromise, [thenable]);
                element('first');
                element('second');
                out.push(Object.isExtensible(element) + '|' + element.length + '|' + element.prototype);
                out
                """;
        assertEquals("true|1|undefined", string(arr(source)));
    }

    // an assignment to a promise's own `then` is what PerformPromiseThen invokes
    @Test
    public void test_own_then_overrides_the_builtin() {
        final var source = """
                let out = [];
                let p = new Promise(() => {});
                p.then = function (onFulfilled, onRejected) {
                    out.push(typeof onFulfilled + ',' + onFulfilled.length + '|' + (this === p));
                };
                Promise.all([p]);
                out
                """;
        assertEquals("function,1|true", string(arr(source)));
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

    // Promise.try hands back the callback's own promise rather than wrapping it again
    @Test
    public void test_try_avoids_wrapping_a_matching_promise() {
        final var source = """
                let out = [];
                let sentinel = Promise.resolve(1);
                out.push(String(Promise.try(() => sentinel) === sentinel));
                out
                """;
        assertEquals("true", string(arr(source)));
    }

    // a throwing callback settles a fresh capability built from the receiver
    @Test
    public void test_try_rejects_through_the_receiver_capability() {
        final var source = """
                let out = [];
                class P extends Promise { constructor(e) { super(e); } }
                let p = P.try(() => { throw 'boom'; });
                p.catch(e => out.push(e + '|' + (p instanceof P)));
                out
                """;
        assertEquals("boom|true", string(arr(source)));
    }
}
