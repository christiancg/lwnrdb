package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;

public class PromiseCapabilityProgramTest {
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

    private static void typeError(String source) {
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_all_keyed_resolves_each_key() {
        assertEquals("1,2", joined("""
                let out = [];
                Promise.allKeyed({ a: Promise.resolve(1), b: 2 }).then(r => out.push(r.a, r.b));
                out
                """));
    }

    @Test
    public void test_all_keyed_keeps_symbol_keys() {
        assertEquals("7", joined("""
                const key = Symbol('k');
                let out = [];
                Promise.allKeyed({ [key]: Promise.resolve(7) }).then(r => out.push(r[key]));
                out
                """));
    }

    @Test
    public void test_all_keyed_rejects() {
        assertEquals("nope", joined("""
                let out = [];
                Promise.allKeyed({ a: Promise.reject(new Error('nope')) }).catch(e => out.push(e.message));
                out
                """));
    }

    @Test
    public void test_combinators_reject_a_primitive_this() {
        typeError("Promise.all.call(1, [])");
        typeError("Promise.allSettled.call(1, [])");
        typeError("Promise.any.call(1, [])");
        typeError("Promise.allKeyed.call(1, {})");
        typeError("Promise.race.call(1, [])");
    }

    @Test
    public void test_resolve_rejects_a_primitive_this() {
        typeError("Promise.resolve.call(1, 2)");
    }

    @Test
    public void test_try_rejects_a_primitive_this() {
        typeError("Promise.try.call(1, () => 1)");
    }

    @Test
    public void test_finally_rejects_a_primitive_this() {
        typeError("Promise.prototype.finally.call(1, () => {})");
    }

    @Test
    public void test_capability_executor_runs_once() {
        typeError("""
                function C(executor) {
                    executor(() => {}, () => {});
                    executor(() => {}, () => {});
                }
                Promise.all.call(C, [])
                """);
    }

    @Test
    public void test_capability_requires_callable_functions() {
        typeError("""
                function C(executor) { executor(1, 2); }
                Promise.all.call(C, [])
                """);
    }

    @Test
    public void test_then_requires_a_promise() {
        typeError("Promise.prototype.then.call({}, () => {})");
    }

    @Test
    public void test_species_rejects_a_primitive_constructor() {
        typeError("""
                const p = Promise.resolve(1);
                p.constructor = 1;
                p.then(() => {})
                """);
    }

    @Test
    public void test_species_rejects_a_non_constructor() {
        typeError("""
                const p = Promise.resolve(1);
                p.constructor = { [Symbol.species]: 5 };
                p.then(() => {})
                """);
    }

    @Test
    public void test_subclass_resolve_and_then() {
        assertEquals("3,true,true", joined("""
                let out = [];
                class My extends Promise {}
                const p = My.resolve(3);
                const chained = p.then(v => out.push(v, p instanceof My, chained instanceof My));
                out
                """));
    }

    @Test
    public void test_try_resolves_a_value() {
        assertEquals("5", joined("let out = []; Promise.try(() => 5).then(v => out.push(v)); out"));
    }

    @Test
    public void test_try_rejects_a_throw() {
        assertEquals("bad", joined("""
                let out = [];
                Promise.try(() => { throw new Error('bad'); }).catch(e => out.push(e.message));
                out
                """));
    }

    @Test
    public void test_try_forwards_arguments() {
        assertEquals("3", joined("let out = []; Promise.try((a, b) => a + b, 1, 2).then(v => out.push(v)); out"));
    }

    @Test
    public void test_with_resolvers_resolve() {
        assertEquals("8", joined("""
                let out = [];
                const { promise, resolve } = Promise.withResolvers();
                promise.then(v => out.push(v));
                resolve(8);
                out
                """));
    }

    @Test
    public void test_with_resolvers_reject() {
        assertEquals("r", joined("""
                let out = [];
                const { promise, reject } = Promise.withResolvers();
                promise.catch(e => out.push(e));
                reject('r');
                out
                """));
    }

    @Test
    public void test_finally_passes_the_value_through() {
        assertEquals("1", joined("let out = []; Promise.resolve(1).finally(() => 99).then(v => out.push(v)); out"));
    }

    @Test
    public void test_finally_passes_a_rejection_through() {
        assertEquals("x", joined("""
                let out = [];
                Promise.reject(new Error('x')).finally(() => {}).catch(e => out.push(e.message));
                out
                """));
    }

    @Test
    public void test_finally_with_a_non_callable_argument() {
        assertEquals("4", joined("let out = []; Promise.resolve(4).finally(1).then(v => out.push(v)); out"));
    }

    @Test
    public void test_finally_callback_that_throws() {
        assertEquals("f", joined("""
                let out = [];
                Promise.resolve(1).finally(() => { throw new Error('f'); }).catch(e => out.push(e.message));
                out
                """));
    }

    @Test
    public void test_all_settled_reports_each_outcome() {
        assertEquals("fulfilled,rejected",
                joined("""
                        let out = [];
                        Promise.allSettled([Promise.resolve(1), Promise.reject('e')]).then(r => out.push(r[0].status, r[1].status));
                        out
                        """));
    }

    @Test
    public void test_any_takes_the_first_fulfilment() {
        assertEquals("2", joined("""
                let out = [];
                Promise.any([Promise.reject('a'), Promise.resolve(2)]).then(v => out.push(v));
                out
                """));
    }

    @Test
    public void test_any_aggregates_rejections() {
        assertEquals("AggregateError,a", joined("""
                let out = [];
                Promise.any([Promise.reject('a')]).catch(e => out.push(e.constructor.name, e.errors[0]));
                out
                """));
    }

    @Test
    public void test_combinators_accept_an_iterable() {
        assertEquals("1,2", joined("""
                let out = [];
                Promise.all(new Set([1, 2])).then(values => out.push(values[0], values[1]));
                out
                """));
    }

    @Test
    public void test_combinator_rejects_a_primitive_iterator() {
        assertEquals("TypeError", joined("""
                let out = [];
                Promise.all({ [Symbol.iterator]() { return 1; } }).catch(e => out.push(e.constructor.name));
                out
                """));
    }

    @Test
    public void test_race_takes_the_first_settlement() {
        assertEquals("1", joined("""
                let out = [];
                Promise.race([Promise.resolve(1), new Promise(() => {})]).then(v => out.push(v));
                out
                """));
    }

    // OrdinaryCreateFromConstructor's Get(newTarget, "prototype") is observable before the executor runs.
    @Test
    public void test_reflect_construct_propagates_a_throwing_prototype_getter() {
        assertThrows(JsThrowException.class, () -> Interpreter.run("""
                const bound = (function() {}).bind();
                Object.defineProperty(bound, 'prototype', {
                    get: function() { throw new Error('boom'); },
                });
                Reflect.construct(Promise, [function() {}], bound);
                """));
    }

    @Test
    public void test_reflect_construct_still_constructs_normally() {
        assertEquals("9", joined("""
                let out = [];
                const p = Reflect.construct(Promise, [(resolve) => resolve(9)]);
                p.then(v => out.push(v));
                out
                """));
    }
}
