package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class IteratorSequencingProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Iterator.concat walks its arguments in order
    @Test
    public void test_concat_in_order() {
        assertEquals("1,2,3", str("[...Iterator.concat([1, 2], [3])].join(',')"));
    }

    // Iterator.concat opens each inner iterable only when it is reached
    @Test
    public void test_concat_opens_lazily() {
        final var source = """
                let opened = 0;
                const lazy = { [Symbol.iterator]() { opened++; return [1].values(); } };
                const it = Iterator.concat([0], lazy);
                it.next();
                opened
                """;
        assertEquals(0, num(source));
    }

    // Closing a concat result closes the inner iterator it is currently on
    @Test
    public void test_concat_return_closes_inner() {
        final var source = """
                let closed = false;
                const src = { [Symbol.iterator]() {
                    return { next() { return { done: false, value: 1 }; },
                             return() { closed = true; return { done: true }; } };
                } };
                const c = Iterator.concat(src);
                c.next();
                c.return();
                closed
                """;
        assertTrue(bool(source));
    }

    // A primitive argument to Iterator.concat is a TypeError
    @Test
    public void test_concat_rejects_primitive() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.concat(1)"));
    }

    // A non-iterable argument to Iterator.concat is a TypeError
    @Test
    public void test_concat_rejects_non_iterable() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.concat({})"));
    }

    // Iterator.concat is not a constructor
    @Test
    public void test_concat_is_not_a_constructor() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Iterator.concat([1])"));
    }

    // Iterator.zip pairs one value from each iterable per round
    @Test
    public void test_zip_pairs_values() {
        assertEquals("1-3,2-4", str("[...Iterator.zip([[1, 2], [3, 4]])].map(p => p.join('-')).join(',')"));
    }

    // The default shortest mode stops at the first exhausted iterable
    @Test
    public void test_zip_shortest_is_the_default() {
        assertEquals(1, num("[...Iterator.zip([[1, 2, 3], [4]])].length"));
    }

    // Longest mode pads an exhausted iterable with undefined
    @Test
    public void test_zip_longest_pads_with_undefined() {
        final var source = """
                [...Iterator.zip([[1, 2], [3]], { mode: 'longest' })].map(p => String(p[1])).join(',')
                """;
        assertEquals("3,undefined", str(source));
    }

    // Longest mode takes each iterable's pad from the padding option
    @Test
    public void test_zip_longest_uses_padding() {
        final var source = """
                [...Iterator.zip([[1, 2], [3]], { mode: 'longest', padding: [0, 9] })]
                        .map(p => String(p[1])).join(',')
                """;
        assertEquals("3,9", str(source));
    }

    // A padding iterable shorter than the input list leaves the rest undefined
    @Test
    public void test_zip_longest_padding_shorter_than_inputs() {
        final var source = """
                [...Iterator.zip([[1, 2], [3]], { mode: 'longest', padding: [0] })]
                        .map(p => String(p[1])).join(',')
                """;
        assertEquals("3,undefined", str(source));
    }

    // Strict mode rejects iterables of differing lengths
    @Test
    public void test_zip_strict_rejects_length_mismatch() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("[...Iterator.zip([[1, 2], [3]], { mode: 'strict' })]"));
    }

    // Strict mode accepts equal lengths
    @Test
    public void test_zip_strict_accepts_equal_lengths() {
        assertEquals(2, num("[...Iterator.zip([[1, 2], [3, 4]], { mode: 'strict' })].length"));
    }

    // An unknown mode is a TypeError
    @Test
    public void test_zip_rejects_unknown_mode() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.zip([[1]], { mode: 'nope' })"));
    }

    // A primitive options argument is a TypeError
    @Test
    public void test_zip_rejects_primitive_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.zip([[1]], 5)"));
    }

    // A primitive padding option is a TypeError
    @Test
    public void test_zip_rejects_primitive_padding() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Iterator.zip([[1]], { mode: 'longest', padding: 5 })"));
    }

    // A primitive iterables argument is rejected before the options are read
    @Test
    public void test_zip_rejects_primitive_iterables_before_options() {
        final var source = """
                let touched = false;
                const options = { get mode() { touched = true; return 'longest'; } };
                try { Iterator.zip(1, options); } catch (e) {}
                touched
                """;
        assertFalse(bool(source));
    }

    // A non-iterable iterables argument is a TypeError
    @Test
    public void test_zip_rejects_non_iterable_argument() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.zip({})"));
    }

    // A primitive element of the iterables list is a TypeError
    @Test
    public void test_zip_rejects_primitive_element() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.zip([1])"));
    }

    // An element whose Symbol.iterator is not callable is a TypeError
    @Test
    public void test_zip_rejects_non_callable_symbol_iterator() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.zip([{ [Symbol.iterator]: 5 }])"));
    }

    // An element whose Symbol.iterator returns a primitive is a TypeError
    @Test
    public void test_zip_rejects_primitive_iterator() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Iterator.zip([{ [Symbol.iterator]() { return 1; } }])"));
    }

    // An element with no Symbol.iterator is treated as the iterator itself
    @Test
    public void test_zip_accepts_a_raw_iterator_element() {
        final var source = """
                const raw = { i: 0, next() { this.i++; return { value: this.i, done: this.i > 2 }; } };
                [...Iterator.zip([raw])].map(p => p[0]).join(',')
                """;
        assertEquals("1,2", str(source));
    }

    // Iterator.zip is not a constructor
    @Test
    public void test_zip_is_not_a_constructor() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Iterator.zip([[1]])"));
    }

    // Iterator.zipKeyed yields an object keyed like its input
    @Test
    public void test_zip_keyed_yields_objects() {
        final var source = """
                [...Iterator.zipKeyed({ a: [1, 2], b: [3, 4] })].map(o => o.a + ':' + o.b).join(',')
                """;
        assertEquals("1:3,2:4", str(source));
    }

    // A symbol key of the input is carried onto each round object
    @Test
    public void test_zip_keyed_keeps_symbol_keys() {
        final var source = """
                const s = Symbol('k');
                const rounds = [...Iterator.zipKeyed({ [s]: [1] })];
                String(rounds[0][s])
                """;
        assertEquals("1", str(source));
    }

    // An undefined-valued key is skipped
    @Test
    public void test_zip_keyed_skips_undefined_values() {
        final var source = """
                const rounds = [...Iterator.zipKeyed({ a: [1], b: undefined })];
                Object.keys(rounds[0]).join(',')
                """;
        assertEquals("a", str(source));
    }

    // A non-enumerable key is skipped
    @Test
    public void test_zip_keyed_skips_non_enumerable_keys() {
        final var source = """
                const input = {};
                Object.defineProperty(input, 'a', { value: [1], enumerable: false });
                [...Iterator.zipKeyed(input)].length
                """;
        assertEquals(0, num(source));
    }

    // A primitive argument to Iterator.zipKeyed is a TypeError
    @Test
    public void test_zip_keyed_rejects_primitive() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Iterator.zipKeyed(1)"));
    }

    // Iterator.zipKeyed is not a constructor
    @Test
    public void test_zip_keyed_is_not_a_constructor() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Iterator.zipKeyed({})"));
    }

    // chunks groups values into arrays of the requested size, with a shorter tail
    @Test
    public void test_chunks_groups_values() {
        assertEquals("12,34,5", str("[...[1, 2, 3, 4, 5].values().chunks(2)].map(c => c.join('')).join(',')"));
    }

    // A chunk size below one is a RangeError
    @Test
    public void test_chunks_rejects_zero_size() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("[1].values().chunks(0)"));
    }

    // A non-numeric chunk size is a TypeError
    @Test
    public void test_chunks_rejects_non_number_size() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].values().chunks('2')"));
    }

    // A fractional chunk size is a TypeError
    @Test
    public void test_chunks_rejects_fractional_size() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].values().chunks(1.5)"));
    }

    // windows yields every sliding window of the requested size
    @Test
    public void test_windows_slides() {
        assertEquals("12,23", str("[...[1, 2, 3].values().windows(2)].map(w => w.join('')).join(',')"));
    }

    // An input shorter than the window yields nothing by default
    @Test
    public void test_windows_only_full_by_default() {
        assertEquals(0, num("[...[1, 2].values().windows(3)].length"));
    }

    // allow-partial yields the undersized window once
    @Test
    public void test_windows_allow_partial() {
        assertEquals("12", str("[...[1, 2].values().windows(3, 'allow-partial')].map(w => w.join('')).join(',')"));
    }

    // An unknown undersized option is a TypeError
    @Test
    public void test_windows_rejects_unknown_undersized_option() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].values().windows(2, 'nope')"));
    }

    // includes reports a value reachable from the iterator
    @Test
    public void test_includes_finds_a_value() {
        assertTrue(bool("[1, 2, 3].values().includes(2)"));
    }

    // includes uses SameValueZero, so NaN matches itself
    @Test
    public void test_includes_matches_nan() {
        assertTrue(bool("[NaN].values().includes(NaN)"));
    }

    // The skipCount argument skips leading values before comparing
    @Test
    public void test_includes_honours_skip_count() {
        assertFalse(bool("[1, 2, 3].values().includes(1, 1)"));
        assertTrue(bool("[1, 2, 1].values().includes(1, 1)"));
    }

    // An infinite skipCount consumes everything and finds nothing
    @Test
    public void test_includes_infinite_skip_count() {
        assertFalse(bool("[1, 2].values().includes(2, Infinity)"));
    }

    // A non-numeric skipCount is a TypeError
    @Test
    public void test_includes_rejects_non_number_skip_count() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].values().includes(1, 'x')"));
    }

    // A fractional skipCount is a TypeError
    @Test
    public void test_includes_rejects_fractional_skip_count() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].values().includes(1, 1.5)"));
    }

    // A negative skipCount is a RangeError
    @Test
    public void test_includes_rejects_negative_skip_count() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("[1].values().includes(1, -1)"));
    }

    // join concatenates the remaining values with the given separator
    @Test
    public void test_join_with_separator() {
        assertEquals("1-2-3", str("[1, 2, 3].values().join('-')"));
    }

    // join defaults to a comma separator
    @Test
    public void test_join_defaults_to_comma() {
        assertEquals("1,2", str("[1, 2].values().join()"));
    }

    // join renders null and undefined as empty strings
    @Test
    public void test_join_skips_nullish_values() {
        assertEquals("1---2", str("[1, null, undefined, 2].values().join('-')"));
    }

    // A value whose string coercion throws closes the iterator and propagates
    @Test
    public void test_join_closes_on_coercion_error() {
        final var source = """
                let closed = false;
                const it = {
                    i: 0,
                    next() { this.i++; return { done: this.i > 1, value: { toString() { throw new Error('boom'); } } }; },
                    return() { closed = true; return { done: true }; }
                };
                try { Iterator.prototype.join.call(it, '-'); } catch (e) {}
                closed
                """;
        assertTrue(bool(source));
    }
}
