package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.test.JsEval;

public class ObjectStaticsBuiltinsTest {
    @Test
    public void test_keys_values_entries() {
        assertEquals("a,b", JsEval.str("Object.keys({a: 1, b: 2}).join(',')"));
        assertEquals("1,2", JsEval.str("Object.values({a: 1, b: 2}).join(',')"));
        assertEquals("a=1,b=2", JsEval.str("Object.entries({a: 1, b: 2}).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    @Test
    public void test_assign() {
        assertEquals(3, JsEval.num("let t = Object.assign({a: 1}, {b: 2}); t.a + t.b"));
        assertEquals(9, JsEval.num("let t = Object.assign({x: 1}, {x: 9}); t.x"));
    }

    @Test
    public void test_get_own_property_names() {
        assertEquals("a,b", JsEval.str("Object.getOwnPropertyNames({a: 1, b: 2}).join(',')"));
    }

    @Test
    public void test_has_own_property() {
        assertTrue(((JsBoolean) Interpreter.run("({a: 1}).hasOwnProperty('a')")).getValue());
        assertFalse(((JsBoolean) Interpreter.run("({a: 1}).hasOwnProperty('b')")).getValue());
        assertFalse(((JsBoolean) Interpreter.run("let p = {a: 1}; let o = Object.create(p); o.hasOwnProperty('a')"))
                .getValue());
    }

    @Test
    public void test_from_entries() {
        assertEquals(3, JsEval.num("let o = Object.fromEntries([['a', 1], ['b', 2]]); o.a + o.b"));
    }

    @Test
    public void test_from_entries_iterable() {
        final var source = """
                function* pairs() { yield ['a', 1]; yield ['b', 4]; }
                let o = Object.fromEntries(pairs());
                o.a + o.b
                """;
        assertEquals(5, JsEval.num(source));
    }

    // A non-object entry - even one reached after already-valid entries - throws per
    // AddEntriesFromIterable step 3.c; it does not just skip that entry.
    @Test
    public void test_from_entries_edge_cases() {
        assertEquals(1, JsEval.num("Object.keys(Object.fromEntries([['a']])).length"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.fromEntries([['a'], 5, ['b', 2]])"));
    }

    @Test
    public void test_prevent_extensions() {
        assertTrue(JsEval.bool("Object.isExtensible({})"));
        assertFalse(JsEval.bool("Object.isExtensible(Object.preventExtensions({}))"));
        assertEquals(5, JsEval.num("let o = Object.preventExtensions({a: 1}); o.a = 5; o.a"));
        assertInstanceOf(JsUndefined.class,
                Interpreter.run("let o = Object.preventExtensions({a: 1}); try { o.b = 9; } catch (e) { } o.b"));
    }

    @Test
    public void test_normal_property_defaults() {
        final var setup = "let o = {}; o.x = 1; ";
        assertTrue(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'x').writable"));
        assertTrue(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'x').enumerable"));
        assertTrue(JsEval.bool(setup + "Object.getOwnPropertyDescriptor(o, 'x').configurable"));
        assertTrue(JsEval.bool(setup + "o.propertyIsEnumerable('x')"));
        assertEquals(5, JsEval.num(setup + "o.x = 5; o.x"));
    }

    @Test
    public void test_has_own() {
        assertTrue(JsEval.bool("Object.hasOwn({a: 1}, 'a')"));
        assertFalse(JsEval.bool("Object.hasOwn({a: 1}, 'b')"));
        assertFalse(JsEval.bool("let p = {a: 1}; let o = Object.create(p); Object.hasOwn(o, 'a')"));
        assertTrue(JsEval.bool("Object.hasOwn([9], 0)"));
        assertTrue(JsEval.bool("Object.hasOwn([9], 'length')"));
        assertFalse(JsEval.bool("Object.hasOwn([9], 1)"));
        assertFalse(JsEval.bool("Object.hasOwn(5, 'x')"));
    }

    @Test
    public void test_group_by() {
        final var setup = "let g = Object.groupBy([1, 2, 3, 4], n => n % 2 === 0 ? 'even' : 'odd'); ";
        assertEquals("1,3", JsEval.str(setup + "g.odd.join(',')"));
        assertEquals("2,4", JsEval.str(setup + "g.even.join(',')"));
    }

    @Test
    public void test_group_by_iterable_index() {
        final var source = "let g = Object.groupBy(new Set(['a', 'b', 'c']), (_, i) => i < 2 ? 'lo' : 'hi'); "
                + "g.lo.join(',') + '|' + g.hi.join(',')";
        assertEquals("a,b|c", JsEval.str(source));
    }

    @Test
    public void test_object_is() {
        assertTrue(JsEval.bool("Object.is(NaN, NaN)"));
        assertFalse(JsEval.bool("Object.is(0, -0)"));
        assertTrue(JsEval.bool("Object.is(0, 0)"));
        assertTrue(JsEval.bool("Object.is(1, 1)"));
        assertFalse(JsEval.bool("Object.is('a', 'b')"));
        assertTrue(JsEval.bool("const o = {}; Object.is(o, o)"));
        assertFalse(JsEval.bool("Object.is({}, {})"));
        assertFalse(JsEval.bool("Object.is(1)"));
    }

    @Test
    public void test_get_own_property_symbols() {
        assertEquals(1, JsEval.num("const s = Symbol('k'); Object.getOwnPropertySymbols({[s]: 1}).length"));
        assertEquals(0, JsEval.num("Object.getOwnPropertySymbols({a: 1}).length"));
        assertEquals(0, JsEval.num("Object.getOwnPropertySymbols(1).length"));
        assertTrue(JsEval.bool("const s = Symbol('k'); Object.getOwnPropertySymbols({[s]: 1})[0] === s"));
    }

    @Test
    public void test_symbol_keys_are_copied() {
        assertEquals(1, JsEval.num("const s = Symbol('k'); Object.assign({}, {[s]: 1})[s]"));
        assertEquals(1, JsEval.num("const s = Symbol('k'); ({...{[s]: 1}})[s]"));
        assertEquals(1, JsEval.num("const s = Symbol('k'); const {...rest} = {[s]: 1}; rest[s]"));
    }

    @Test
    public void test_function_name_is_an_own_property() {
        assertTrue(JsEval.bool("Object.prototype.hasOwnProperty.call(Array.prototype.join, 'name')"));
        assertTrue(JsEval.bool("function f(){} Object.hasOwn(f, 'name')"));
    }

    @Test
    public void test_function_length_is_an_own_property() {
        assertTrue(JsEval.bool("function f(a, b){} Object.hasOwn(f, 'length')"));
    }

    @Test
    public void test_get_own_property_names_of_a_function_includes_name_and_length() {
        assertEquals("[\"length\",\"name\",\"prototype\",\"x\"]",
                JsEval.str("function f(a, b){} f.x = 1; JSON.stringify(Object.getOwnPropertyNames(f))"));
    }

    @Test
    public void test_object_called_as_function() {
        assertTrue(JsEval.bool("typeof Object(5) === 'object'"));
        assertTrue(JsEval.bool("let o = {a: 1}; Object(o) === o"));
        assertTrue(JsEval.bool("let a = [1]; Object(a) === a"));
        assertTrue(JsEval.bool("let f = function() {}; Object(f) === f"));
        assertTrue(JsEval.bool("typeof Object() === 'object'"));
    }

    @Test
    public void test_has_own_requires_two_args() {
        assertFalse(JsEval.bool("Object.hasOwn({a: 1})"));
    }

    @Test
    public void test_values_entries_over_proxy() {
        assertEquals("1,2", JsEval.str("Object.values(new Proxy({a: 1, b: 2}, {})).join(',')"));
        assertEquals("a=1,b=2",
                JsEval.str("Object.entries(new Proxy({a: 1, b: 2}, {})).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    @Test
    public void test_values_entries_over_function() {
        assertEquals("1", JsEval.str("function f() {} f.x = 1; Object.values(f).join(',')"));
        assertEquals("x=1",
                JsEval.str("function f() {} f.x = 1; Object.entries(f).map(e => e[0] + '=' + e[1]).join(',')"));
    }

    @Test
    public void test_assign_non_object_target() {
        // Object.assign always ToObjects the target, even with no sources at all, so a primitive
        assertEquals("object", JsEval.str("typeof Object.assign(5)"));
        assertEquals(5, JsEval.num("Object.assign(5).valueOf()"));
    }

    @Test
    public void test_get_own_property_names_over_proxy() {
        assertEquals("a,b", JsEval.str("Object.getOwnPropertyNames(new Proxy({a: 1, b: 2}, {})).join(',')"));
    }

    @Test
    public void test_get_own_property_names_over_global_this() {
        assertTrue(JsEval.bool("Object.getOwnPropertyNames(globalThis).includes('NaN')"));
    }

    @Test
    public void assignRejectsANullishTarget() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.assign(null, {a: 1})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.assign(undefined, {a: 1})"));
    }

    @Test
    public void assignThrowsWhenAWriteIsRejected() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.assign(Object.freeze({a: 1}), {a: 2})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                const s = Symbol('s');
                const target = Object.preventExtensions({});
                const source = {};
                source[s] = 2;
                Object.assign(target, source)
                """));
    }

    @Test
    public void ownPropertyNamesCoverClassesAndExoticTargets() {
        assertTrue(JsEval.bool("class C { static x = 1; } Object.getOwnPropertyNames(C).includes('x')"));
        assertTrue(JsEval.bool("const s = Symbol('s'); class C { static [s] = 1; } Object.hasOwn(C, s)"));
        assertEquals("x", JsEval.str("const m = new Map(); Object.defineProperty(m, 'x', { value: 1 });"
                + " Object.getOwnPropertyNames(m).join(',')"));
    }

    @Test
    public void hasOwnPropertyFallsBackToAnExoticTypesTable() {
        assertTrue(JsEval.bool("const d = new Date(0); d.foo = 1; d.hasOwnProperty('foo')"));
        assertTrue(JsEval.bool("const m = new Map(); m.foo = 1; m.hasOwnProperty('foo')"));
        assertTrue(JsEval.bool("const re = /x/; re.hasOwnProperty('lastIndex')"));
        assertTrue(JsEval.bool("const p = Promise.resolve(1); p.foo = 1; p.hasOwnProperty('foo')"));
        assertTrue(JsEval.bool("""
                function f() {}
                const bound = f.bind({});
                Object.defineProperty(bound, 'x', { set(v) {} });
                bound.hasOwnProperty('x')
                """));
    }

    @Test
    public void hasOwnPropertyAndGetOwnPropertySymbolsAreProxyAware() {
        assertTrue(JsEval.bool("""
                const target = { attr: 1 };
                const p = new Proxy(target, {});
                p.hasOwnProperty('attr')
                """));
        assertFalse(JsEval.bool("""
                const p = new Proxy({}, { getOwnPropertyDescriptor: () => undefined });
                p.hasOwnProperty('attr')
                """));
        assertEquals(1, JsEval.num("""
                const s = Symbol('s');
                const p = new Proxy({}, { ownKeys: () => [s] , getOwnPropertyDescriptor: () => ({
                    value: 1, writable: true, enumerable: true, configurable: true
                }) });
                Object.getOwnPropertySymbols(p).length
                """));
    }

    @Test
    public void assignConsultsAProxySourcesTrapsExactlyOnce() {
        assertEquals(1, JsEval.num("""
                let calls = 0;
                const p = new Proxy({}, {
                    ownKeys: () => ['a'],
                    getOwnPropertyDescriptor: () => { calls++; return undefined; }
                });
                Object.assign({}, p);
                calls
                """));
        assertTrue(JsEval.bool("""
                let caught = null;
                try {
                    const p = new Proxy({}, { ownKeys: () => { throw new RangeError('boom'); } });
                    Object.assign({}, p);
                } catch (e) { caught = e; }
                caught instanceof RangeError
                """));
    }

    @Test
    public void fromEntriesRejectsNonObjectEntriesAndClosesTheIterator() {
        assertTrue(JsEval.bool("""
                let returned = false;
                const iterable = {
                    [Symbol.iterator]() {
                        let done = false;
                        return {
                            next() { const d = done; done = true; return { done: d, value: 'nope' }; },
                            return() { returned = true; },
                        };
                    },
                };
                let threw = false;
                try { Object.fromEntries(iterable); } catch (e) { threw = e instanceof TypeError; }
                threw && returned
                """));
    }

    @Test
    public void groupByRejectsNonCallableAndUsesRealToPropertyKey() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.groupBy([], null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.groupBy([], {})"));
        assertEquals("1", JsEval.str("""
                const stringable = { toString() { return 1; } };
                const g = Object.groupBy([1, '1', stringable], v => v);
                Object.keys(g).join(',')
                """));
    }

    @Test
    public void groupByPropagatesAPoisonedPropertyKeyConversion() {
        assertTrue(JsEval.bool("""
                let caught = null;
                try {
                    Object.groupBy([1], () => ({ toString() { throw new RangeError('nope'); } }));
                } catch (e) { caught = e; }
                caught instanceof RangeError
                """));
    }
}
