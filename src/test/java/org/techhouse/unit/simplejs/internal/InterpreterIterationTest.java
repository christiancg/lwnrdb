package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;

public class InterpreterIterationTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_spread_generator_into_array() {
        assertEquals("1,2,3", str("function* g() { yield 1; yield 2; yield 3; } [...g()].join(',')"));
    }

    @Test
    public void test_spread_custom_iterable() {
        final var source = """
                let obj = {
                    [Symbol.iterator]() {
                        let i = 1;
                        return { next() { return i <= 3 ? {value: i++, done: false} : {value: 0, done: true}; } };
                    }
                };
                [...obj].join(',')
                """;
        assertEquals("1,2,3", str(source));
    }

    @Test
    public void test_iterator_map_filter_take_chain() {
        final var source = """
                function* g() { yield 1; yield 2; yield 3; yield 4; }
                [...g().map(x => x * 2).filter(x => x > 4).take(1)][0]
                """;
        assertEquals(6, num(source));
    }

    @Test
    public void test_iterator_drop_flat_map() {
        assertEquals("3,4", str("function* g(){yield 1;yield 2;yield 3;yield 4;} g().drop(2).toArray().join(',')"));
        assertEquals("1,10,2,20",
                str("function* g(){yield 1;yield 2;} g().flatMap(x => [x, x * 10]).toArray().join(',')"));
    }

    @Test
    public void test_iterator_some_every_find() {
        assertTrue(bool("function* g(){yield 1;yield 2;yield 3;} g().some(x => x === 2)"));
        assertFalse(bool("function* g(){yield 1;yield 2;yield 3;} g().every(x => x < 3)"));
        assertEquals(4, num("function* g(){yield 2;yield 4;yield 6;} g().find(x => x > 3)"));
    }

    @Test
    public void test_iterator_helpers_are_lazy() {
        final var source = """
                let pulled = 0;
                function* g() { while (true) { pulled++; yield pulled; } }
                let taken = [...g().take(2)];
                taken.length * 100 + pulled
                """;
        assertEquals(202, num(source));
    }

    @Test
    public void test_array_values_inherits_helpers() {
        assertEquals("2,3,4", str("[1,2,3].values().map(x => x + 1).toArray().join(',')"));
    }

    @Test
    public void test_iterator_from_plain_iterator() {
        final var source = """
                let i = 0;
                const it = { next() { i++; return i <= 3 ? {value: i, done: false} : {value: undefined, done: true}; } };
                Iterator.from(it).map(x => x * x).toArray().join(',')
                """;
        assertEquals("1,4,9", str(source));
    }
    @Test
    public void test_spread_string_astral() {
        assertEquals(3, num("[...'ab\\u{1F600}'].length"));
        assertEquals("😀", str("[...'ab\\u{1F600}'][2]"));
        assertEquals(2, num("[...'\\u{1F600}\\u{1F600}'].length"));
        assertEquals(0, num("[...''].length"));
    }

    @Test
    public void test_destructure_string_astral() {
        assertEquals("😀", str("const [a, b] = 'a\\u{1F600}'; b"));
    }

    @Test
    public void test_array_from_string_astral() {
        assertEquals(2, num("Array.from('a\\u{1F600}').length"));
        assertEquals(1, num("[...('\\u{1F600}')[Symbol.iterator]()].length"));
    }

    @Test
    public void test_string_index_stays_code_unit() {
        assertTrue(bool("'\\u{1F600}'[0].length === 1"));
        assertEquals(2, num("'\\u{1F600}'.length"));
        assertEquals(3, num("'a\\u{1F600}'.split('').length"));
        assertEquals("0,1", str("Object.keys({...'\\u{1F600}'}).join(',')"));
        assertEquals(2, num("Array.prototype.slice.call('\\u{1F600}').length"));
    }

    @Test
    public void test_lone_surrogate_is_preserved() {
        assertEquals(2, num("[...'\\uD800x'].length"));
        assertEquals(1, num("[...'\\uD800x'][0].length"));
    }

    @Test
    public void test_spread_object_with_generator_symbol_iterator() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 2; } }
                [...new C()].join(',')
                """;
        assertEquals("1,2", str(source));
    }

    @Test
    public void test_spread_object_literal_generator_method() {
        assertEquals("1", str("[...{ *[Symbol.iterator]() { yield 1; } }].join(',')"));
    }

    @Test
    public void test_symbol_iterator_as_plain_generator_function_property() {
        final var source = """
                const o = { [Symbol.iterator]: function* () { yield 1; yield 2; } };
                [...o].join(',')
                """;
        assertEquals("1,2", str(source));
    }

    @Test
    public void test_array_destructuring_from_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 4; yield 5; } }
                const [a, b] = new C(); a * 10 + b
                """;
        assertEquals(45, num(source));
    }

    @Test
    public void test_array_from_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 2; yield 3; } }
                Array.from(new C()).length
                """;
        assertEquals(3, num(source));
    }

    @Test
    public void test_yield_star_over_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 2; } }
                function* g() { yield* new C(); yield 3; }
                [...g()].join(',')
                """;
        assertEquals("1,2,3", str(source));
    }

    @Test
    public void test_new_set_from_generator_iterable() {
        final var source = """
                class C { *[Symbol.iterator]() { yield 1; yield 1; yield 2; } }
                new Set(new C()).size
                """;
        assertEquals(2, num(source));
    }

    @Test
    public void test_symbol_iterator_returning_map_iterator() {
        final var source = """
                const o = { [Symbol.iterator]() { return new Map([[1, 2]])[Symbol.iterator](); } };
                [...o][0].join(',')
                """;
        assertEquals("1,2", str(source));
    }

    @Test
    public void test_symbol_iterator_returning_primitive_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[...{ [Symbol.iterator]() { return 1; } }]"));
    }

    @Test
    public void test_symbol_iterator_returning_undefined_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("[...{ [Symbol.iterator]() { return undefined; } }]"));
    }

    @Test
    public void test_generator_iterable_close_runs_finally() {
        final var source = """
                let closed = false;
                class C {
                    *[Symbol.iterator]() {
                        try { yield 1; yield 2; } finally { closed = true; }
                    }
                }
                for (const x of new C()) { break; }
                closed
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_patched_next_on_generator_iterable() {
        final var source = """
                function* seed() { yield 1; }
                const proto = Object.getPrototypeOf(Object.getPrototypeOf(seed()));
                const original = proto.next;
                proto.next = function () { return { value: 9, done: true }; };
                const o = { [Symbol.iterator]() { return (function* () { yield 1; yield 2; })(); } };
                const count = [...o].length;
                proto.next = original;
                count
                """;
        assertEquals(0, num(source));
    }

    @Test
    public void test_is_object_like_covers_non_primitives() {
        assertTrue(InterpreterUtils.isObjectLike(new JsObject()));
        assertTrue(InterpreterUtils.isObjectLike(new JsArray()));
        assertTrue(InterpreterUtils.isObjectLike(new JsMap()));
        assertTrue(InterpreterUtils.isObjectLike(new JsSet()));
        assertTrue(InterpreterUtils.isObjectLike(new JsDate(0)));
        assertTrue(InterpreterUtils.isObjectLike(new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance())));
    }

    @Test
    public void test_is_object_like_rejects_primitives() {
        assertFalse(InterpreterUtils.isObjectLike(JsUndefined.getInstance()));
        assertFalse(InterpreterUtils.isObjectLike(JsNull.getInstance()));
        assertFalse(InterpreterUtils.isObjectLike(JsBoolean.TRUE));
        assertFalse(InterpreterUtils.isObjectLike(new JsNumber(1)));
        assertFalse(InterpreterUtils.isObjectLike(new JsString("a")));
        assertFalse(InterpreterUtils.isObjectLike(new JsBigInt(java.math.BigInteger.ONE)));
        assertFalse(InterpreterUtils.isObjectLike(new JsSymbol("s")));
    }
    @Test
    public void test_iterator_symbol_is_a_real_prototype_property() {
        assertTrue(bool("Array.prototype.values === Array.prototype[Symbol.iterator]"));
        assertTrue(bool("Map.prototype.entries === Map.prototype[Symbol.iterator]"));
        assertTrue(bool("Set.prototype.values === Set.prototype[Symbol.iterator]"));
        assertTrue(bool("typeof String.prototype[Symbol.iterator] === 'function'"));
        assertTrue(bool("[1][Symbol.iterator] === Array.prototype[Symbol.iterator]"));
    }

    @Test
    public void test_deleted_array_iterator_makes_arrays_non_iterable() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("delete Array.prototype[Symbol.iterator]; const [a] = [1];"));
    }

    @Test
    public void test_array_iterator_reads_the_array_lazily() {
        final var source = """
                const a = [1, 2, 3];
                const it = a[Symbol.iterator]();
                it.next();
                a[1] = 8;
                it.next().value
                """;
        assertEquals(8, num(source));
    }

    @Test
    public void test_proxy_over_an_array_is_iterable() {
        assertEquals(6, num("let s = 0; for (const x of new Proxy([1, 2, 3], {})) s += x; s"));
    }
}
