package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class IteratorProtocolProgramTest {
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
    public void test_from_wraps_a_plain_iterator() {
        final var source = """
                const w = Iterator.from({ i: 0, next() { return { value: this.i++, done: this.i > 2 }; } });
                w.next().value + ',' + w.next().value
                """;
        assertEquals("0,1", str(source));
    }

    @Test
    public void test_from_passes_an_iterator_through() {
        assertTrue(bool("const g = (function* () {})(); Iterator.from(g) === g"));
    }

    @Test
    public void test_from_accepts_a_string() {
        assertEquals("ab", str("[...Iterator.from('ab')].join('')"));
    }

    @Test
    public void test_from_rejects_a_number() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.from(1)"));
    }

    @Test
    public void test_from_rejects_non_callable_symbol_iterator() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.from({ [Symbol.iterator]: 1 })"));
    }

    @Test
    public void test_from_rejects_primitive_iterator() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Iterator.from({ [Symbol.iterator]() { return 1; } })"));
    }

    @Test
    public void test_from_wrapper_requires_a_callable_next() {
        final var source = """
                const w = Iterator.from({ next: 1, [Symbol.iterator]() { return this; } });
                w.next()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_from_wrapper_forwards_return() {
        final var source = """
                let closed = false;
                const w = Iterator.from({
                    next() { return { done: false, value: 1 }; },
                    return() { closed = true; return { done: true }; }
                });
                w.return();
                closed
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_from_wrapper_return_without_target_method() {
        assertTrue(bool("Iterator.from({ next() { return { done: true }; } }).return().done"));
    }

    @Test
    public void test_from_wrapper_rejects_non_callable_return() {
        final var source = """
                const w = Iterator.from({ next() { return { done: true }; }, return: 1 });
                w.return()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_iterator_is_not_directly_constructable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Iterator()"));
    }

    @Test
    public void test_iterator_subclass_constructs() {
        final var source = """
                class My extends Iterator { next() { return { done: true }; } }
                new My() instanceof Iterator
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_constructor_setter_refuses_the_home_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.prototype.constructor = 1"));
    }

    @Test
    public void test_constructor_setter_defines_on_a_derived_receiver() {
        final var source = """
                const derived = Object.create(Iterator.prototype);
                derived.constructor = 7;
                derived.constructor
                """;
        assertEquals(7, num(source));
    }

    @Test
    public void test_constructor_setter_updates_an_existing_own_property() {
        final var source = """
                const derived = Object.create(Iterator.prototype);
                derived.constructor = 7;
                derived.constructor = 8;
                derived.constructor
                """;
        assertEquals(8, num(source));
    }

    @Test
    public void test_to_string_tag_setter_defines_on_a_derived_receiver() {
        final var source = """
                const derived = Object.create(Iterator.prototype);
                derived[Symbol.toStringTag] = 'X';
                derived[Symbol.toStringTag]
                """;
        assertEquals("X", str(source));
    }

    @Test
    public void test_iterator_prototype_tag() {
        assertEquals("Iterator", str("Iterator.prototype[Symbol.toStringTag]"));
    }

    @Test
    public void test_constructor_setter_rejects_a_primitive_receiver() {
        final var source = """
                const setter = Object.getOwnPropertyDescriptor(Iterator.prototype, 'constructor').set;
                setter.call(1, 2)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_helper_next_brand_check() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const h = [1].values().map(x => x); h.next.call({})"));
    }

    @Test
    public void test_helper_return_brand_check() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const h = [1].values().map(x => x); h.return.call({})"));
    }

    @Test
    public void test_helper_rejects_re_entry() {
        final var source = """
                let it;
                it = [1, 2].values().map(x => { it.next(); return x; });
                it.next()
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_helper_return_closes_the_source() {
        final var source = """
                let closed = false;
                const it = {
                    next() { return { done: false, value: 1 }; },
                    return() { closed = true; return { done: true }; }
                };
                Iterator.prototype.map.call(it, x => x).return();
                closed
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_helper_return_result_shape() {
        final var source = """
                const result = [1].values().map(x => x).return();
                String(result.done) + ',' + String(result.value)
                """;
        assertEquals("true,undefined", str(source));
    }

    @Test
    public void test_helper_to_string_tag() {
        assertEquals("[object Iterator Helper]", str("Object.prototype.toString.call([1].values().map(x => x))"));
    }

    @Test
    public void test_dispose_closes_the_iterator() {
        final var source = """
                let closed = false;
                const it = {
                    next() { return { done: false, value: 1 }; },
                    return() { closed = true; return { done: true }; },
                    [Symbol.iterator]() { return this; }
                };
                Iterator.prototype[Symbol.dispose].call(it);
                closed
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_dispose_rejects_non_callable_return() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Iterator.prototype[Symbol.dispose].call({ return: 1 })"));
    }

    @Test
    public void test_dispose_without_return_method() {
        final var source = """
                let ran = false;
                Iterator.prototype[Symbol.dispose].call({ next() { return { done: true }; } });
                ran = true;
                ran
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_helper_rejects_a_primitive_receiver() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.prototype.map.call(1, x => x)"));
    }

    @Test
    public void test_helper_rejects_a_non_callable_callback() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].values().map(1)"));
    }

    @Test
    public void test_helper_requires_a_callable_next() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.prototype.toArray.call({ next: 1 })"));
    }

    @Test
    public void test_invalid_argument_closes_the_receiver() {
        final var source = """
                let closed = false;
                const it = {
                    next() { return { done: false, value: 1 }; },
                    return() { closed = true; return { done: true }; }
                };
                try { Iterator.prototype.take.call(it, NaN); } catch (e) {}
                closed
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_take_rejects_nan() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("[1].values().take(NaN)"));
    }

    @Test
    public void test_take_rejects_a_negative_limit() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("[1].values().take(-1)"));
    }

    @Test
    public void test_take_accepts_infinity() {
        assertEquals(2, num("[1, 2].values().take(Infinity).toArray().length"));
    }

    @Test
    public void test_flat_map_rejects_a_primitive_mapping() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[...[1].values().flatMap(x => x)]"));
    }

    @Test
    public void test_flat_map_closes_the_source_on_error() {
        final var source = """
                let closed = false;
                const it = {
                    next() { return { done: false, value: 1 }; },
                    return() { closed = true; return { done: true }; }
                };
                try { Iterator.prototype.flatMap.call(it, () => 1).next(); } catch (e) {}
                closed
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_flat_map_return_closes_inner_and_outer() {
        final var source = """
                let closed = '';
                const inner = {
                    next() { return { done: false, value: 1 }; },
                    return() { closed += 'inner'; return { done: true }; },
                    [Symbol.iterator]() { return this; }
                };
                const outer = {
                    next() { return { done: false, value: inner }; },
                    return() { closed += 'outer'; return { done: true }; }
                };
                const h = Iterator.prototype.flatMap.call(outer, v => v);
                h.next();
                h.return();
                closed
                """;
        assertEquals("innerouter", str(source));
    }

    @Test
    public void test_callback_error_closes_the_source() {
        final var source = """
                let closed = false;
                const it = {
                    next() { return { done: false, value: 1 }; },
                    return() { closed = true; return { done: true }; }
                };
                try { Iterator.prototype.forEach.call(it, () => { throw new Error('boom'); }); } catch (e) {}
                closed
                """;
        assertTrue(bool(source));
    }

    @Test
    public void test_every_closes_on_first_false() {
        final var source = """
                let closed = false;
                const it = {
                    i: 0,
                    next() { this.i++; return { done: false, value: this.i }; },
                    return() { closed = true; return { done: true }; }
                };
                const result = Iterator.prototype.every.call(it, v => v < 2);
                String(result) + ',' + String(closed)
                """;
        assertEquals("false,true", str(source));
    }

    @Test
    public void test_every_on_empty_is_true() {
        assertTrue(bool("[].values().every(() => false)"));
    }

    @Test
    public void test_some_on_empty_is_false() {
        assertEquals("false", str("String([].values().some(() => true))"));
    }

    @Test
    public void test_reduce_rejects_an_empty_iterator() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].values().reduce((a, b) => a + b)"));
    }
}
