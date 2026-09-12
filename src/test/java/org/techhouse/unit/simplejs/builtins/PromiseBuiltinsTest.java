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

    private static double num(JsArray array) {
        return ((JsNumber) array.get(0)).getValue();
    }

    private static String string(JsArray array) {
        return ((JsString) array.get(0)).getValue();
    }

    @Test
    public void test_aggregate_error_constructor() {
        final var source = "let e = new AggregateError(['x', 'y'], 'oops');"
                + " e.name + '|' + e.message + '|' + e.errors.join(',')";
        assertEquals("AggregateError|oops|x,y", ((JsString) Interpreter.run(source)).getValue());
    }

    @Test
    public void test_resolve() {
        assertEquals(5, num(arr("let out = []; Promise.resolve(5).then(v => out.push(v)); out")));
    }

    @Test
    public void test_reject_and_catch() {
        assertEquals("c:e", string(arr("let out = []; Promise.reject('e').catch(v => out.push('c:' + v)); out")));
    }

    @Test
    public void test_then_chaining() {
        assertEquals(2, num(arr("let out = []; Promise.resolve(1).then(v => v + 1).then(v => out.push(v)); out")));
    }

    @Test
    public void test_then_handler_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve(1).then(() => { throw 'oops'; }).catch(e => out.push('c:' + e));
                out
                """;
        assertEquals("c:oops", string(arr(source)));
    }

    @Test
    public void test_new_promise_resolve() {
        assertEquals(9, num(arr("let out = []; new Promise((res, rej) => res(9)).then(v => out.push(v)); out")));
    }

    @Test
    public void test_new_promise_reject() {
        assertEquals("x",
                string(arr("let out = []; new Promise((res, rej) => rej('x')).catch(v => out.push(v)); out")));
    }

    @Test
    public void test_new_promise_executor_throw_rejects() {
        final var source = """
                let out = [];
                new Promise((res, rej) => { throw 'bad'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("bad", string(arr(source)));
    }

    @Test
    public void test_new_promise_bad_resolver_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Promise(5)"));
    }

    @Test
    public void test_with_resolvers_resolve() {
        final var source = """
                let out = [];
                let { promise, resolve } = Promise.withResolvers();
                promise.then(v => out.push(v));
                resolve(7);
                out
                """;
        assertEquals(7, num(arr(source)));
    }

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

    @Test
    public void test_try_fulfils() {
        final var source = """
                let out = [];
                Promise.try((a, b) => a + b, 2, 3).then(v => out.push(v));
                out
                """;
        assertEquals(5, num(arr(source)));
    }

    @Test
    public void test_try_rejects_on_throw() {
        final var source = """
                let out = [];
                Promise.try(() => { throw 'nope'; }).catch(e => out.push(e));
                out
                """;
        assertEquals("nope", string(arr(source)));
    }

    @Test
    public void test_try_adopts_promise() {
        final var source = """
                let out = [];
                Promise.try(() => Promise.resolve(11)).then(v => out.push(v));
                out
                """;
        assertEquals(11, num(arr(source)));
    }

    @Test
    public void test_resolve_assimilates_thenable() {
        final var source = """
                let out = [];
                Promise.resolve({ then(res) { res(42); } }).then(v => out.push(v));
                out
                """;
        assertEquals(42, num(arr(source)));
    }

    @Test
    public void test_thenable_sync_throw_rejects() {
        final var source = """
                let out = [];
                Promise.resolve({ then() { throw 'boom'; } }).catch(e => out.push(e));
                out
                """;
        assertEquals("boom", string(arr(source)));
    }

    @Test
    public void test_thenable_never_settling_stays_pending() {
        final var source = """
                let out = [];
                Promise.resolve({ then() {} }).then(v => out.push(v), e => out.push(e));
                out
                """;
        assertEquals(0, arr(source).length());
    }

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

    @Test
    public void test_combinator_on_a_non_constructor_this_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Promise.all.call(Math.max, [])"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Promise.resolve.call(Math.max, 1)"));
    }

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

    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Promise(function() {})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Promise.call(null, function() {})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("let p = new Promise(function() {}); Promise.call(p, function() {})"));
    }

    @Test
    public void test_subclass_construction_still_works() {
        final var source = """
                let out = [];
                class P extends Promise {}
                let p = new P(resolve => resolve(1));
                p.then(v => out.push(v + '|' + (p instanceof P)));
                out
                """;
        assertEquals("1|true", string(arr(source)));
    }
}
