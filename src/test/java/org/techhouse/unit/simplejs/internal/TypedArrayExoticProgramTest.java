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

public class TypedArrayExoticProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("new Float64Array([NaN]).includes(NaN)")).getValue();
    }

    @Test
    public void test_construct_over_a_buffer() {
        assertEquals(2, num("new Int8Array(new ArrayBuffer(4), 1, 2).length"));
    }

    @Test
    public void test_construct_rejects_a_detached_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4);
                buffer.transfer();
                new Int8Array(buffer)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_construct_rejects_a_negative_length() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(-1)"));
    }

    @Test
    public void test_subarray_shares_the_buffer() {
        final var source = """
                const t = new Int8Array([1, 2, 3, 4]);
                const s = t.subarray(1);
                s[0] = 9;
                t[1]
                """;
        assertEquals(9, num(source));
    }

    @Test
    public void test_subarray_bounds() {
        assertEquals("2,3", str("new Int8Array([1, 2, 3, 4]).subarray(1, 3).join(',')"));
        assertEquals("3,4", str("new Int8Array([1, 2, 3, 4]).subarray(-2).join(',')"));
    }

    @Test
    public void test_slice_copies() {
        final var source = """
                const t = new Int8Array([1, 2, 3, 4]);
                const copy = t.slice(1, 3);
                copy[0] = 9;
                copy.join(',') + ':' + t.join(',')
                """;
        assertEquals("9,3:1,2,3,4", str(source));
    }

    @Test
    public void test_map_uses_the_species_constructor() {
        final var source = """
                const t = new Int8Array([1, 2]);
                t.constructor = { [Symbol.species]: Int16Array };
                const mapped = t.map(x => x * 2);
                mapped.join(',') + ':' + String(mapped instanceof Int16Array)
                """;
        assertEquals("2,4:true", str(source));
    }

    @Test
    public void test_filter_and_slice_use_the_species_constructor() {
        final var source = """
                const t = new Int8Array([1, 2, 3]);
                t.constructor = { [Symbol.species]: Int16Array };
                String(t.filter(x => x > 1) instanceof Int16Array) + ',' + String(t.slice(1) instanceof Int16Array)
                """;
        assertEquals("true,true", str(source));
    }

    @Test
    public void test_null_species_falls_back_to_the_default() {
        final var source = """
                const t = new Int8Array([1, 2]);
                t.constructor = { [Symbol.species]: null };
                t.map(x => x * 2).join(',')
                """;
        assertEquals("2,4", str(source));
    }

    @Test
    public void test_undefined_constructor_falls_back_to_the_default() {
        final var source = """
                const t = new Int8Array([1, 2]);
                t.constructor = undefined;
                t.map(x => x * 2).join(',')
                """;
        assertEquals("2,4", str(source));
    }

    @Test
    public void test_primitive_constructor_property_is_rejected() {
        final var source = """
                const t = new Int8Array([1]);
                t.constructor = 5;
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_non_callable_species_is_rejected() {
        final var source = """
                const t = new Int8Array([1]);
                t.constructor = { [Symbol.species]: 5 };
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_species_must_return_a_typed_array() {
        final var source = """
                function NotTyped() { return {}; }
                const t = new Int8Array([1]);
                t.constructor = { [Symbol.species]: NotTyped };
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_species_must_return_a_long_enough_array() {
        final var source = """
                function Small() { return new Int8Array(1); }
                const t = new Int8Array([1, 2]);
                t.constructor = { [Symbol.species]: Small };
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_species_cannot_change_the_content_type() {
        final var source = """
                const t = new Int8Array([1, 2]);
                t.constructor = { [Symbol.species]: BigInt64Array };
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_to_locale_string() {
        assertEquals("1,2,3", str("new Int8Array([1, 2, 3]).toLocaleString()"));
    }

    @Test
    public void test_to_locale_string_of_an_empty_array() {
        assertEquals("", str("new Int8Array(0).toLocaleString()"));
    }

    @Test
    public void test_to_locale_string_of_bigints() {
        assertEquals("1,2", str("new BigInt64Array([1n, 2n]).toLocaleString()"));
    }

    @Test
    public void test_from_with_a_mapper() {
        final var source = """
                const t = Int8Array.from([1, 2, 3], x => x * 2);
                t.join(',') + ':' + String(t instanceof Int8Array)
                """;
        assertEquals("2,4,6:true", str(source));
    }

    @Test
    public void test_from_with_a_custom_constructor() {
        final var source = """
                let seen = 0;
                function C(n) { seen = n; return new Int8Array(n); }
                const result = Int8Array.from.call(C, [1, 2]);
                seen + ':' + result.join(',')
                """;
        assertEquals("2:1,2", str(source));
    }

    @Test
    public void test_from_rejects_a_primitive_this() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Int8Array.from.call(5, [1])"));
    }

    @Test
    public void test_of_rejects_a_primitive_this() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Int8Array.of.call(5, 1)"));
    }

    @Test
    public void test_from_an_iterable() {
        assertEquals("1,2", str("Int8Array.from(new Set([1, 2])).join(',')"));
    }

    @Test
    public void test_sort_rejects_a_non_callable_comparator() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array([2, 1]).sort(5)"));
    }

    @Test
    public void test_sort_treats_nan_as_zero() {
        assertEquals("2,1", str("new Int8Array([2, 1]).sort(() => NaN).join(',')"));
    }

    @Test
    public void test_includes_finds_nan() {
        assertTrue(bool());
    }

    @Test
    public void test_includes_on_an_empty_array() {
        assertEquals("false", str("String(new Int8Array(0).includes(1))"));
    }

    @Test
    public void test_find_last() {
        assertEquals("2,1", str(
                "String(new Int8Array([1, 2, 3]).findLast(x => x < 3)) + ',' + new Int8Array([1, 2, 3]).findLastIndex(x => x < 3)"));
    }

    @Test
    public void test_reduce_right() {
        assertEquals("321", str("new Int8Array([1, 2, 3]).reduceRight((a, b) => a + '' + b)"));
    }

    @Test
    public void test_reduce_rejects_an_empty_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array(0).reduce((a, b) => a + b)"));
    }

    @Test
    public void test_copy_within() {
        assertEquals("3,4,3,4", str("new Int8Array([1, 2, 3, 4]).copyWithin(0, 2).join(',')"));
    }

    @Test
    public void test_with_replaces_an_element() {
        assertEquals("9,2", str("new Int8Array([1, 2]).with(0, 9).join(',')"));
    }

    @Test
    public void test_by_copy_methods() {
        final var source = """
                const t = new Int8Array([3, 1]);
                t.toSorted().join(',') + ':' + t.toReversed().join(',') + ':' + t.join(',')
                """;
        assertEquals("1,3:1,3:3,1", str(source));
    }

    @Test
    public void test_iteration_helpers() {
        final var source = """
                const t = new Int8Array([5, 6]);
                [...t.keys()].join('') + ':' + [...t.values()].join('') + ':'
                        + [...t.entries()].map(e => e.join('-')).join(',')
                """;
        assertEquals("01:56:0-5,1-6", str(source));
    }

    @Test
    public void test_at() {
        assertEquals("2,undefined",
                str("String(new Int8Array([1, 2]).at(-1)) + ',' + String(new Int8Array([1]).at(5))"));
    }

    @Test
    public void test_uint8_clamped_writes() {
        final var source = """
                const t = new Uint8ClampedArray(2);
                t[0] = 300;
                t[1] = -5;
                t.join(',')
                """;
        assertEquals("255,0", str(source));
    }

    @Test
    public void test_big_uint64_wraps() {
        assertEquals("18446744073709551615", str("const t = new BigUint64Array(1); t[0] = -1n; String(t[0])"));
    }

    @Test
    public void test_bigint_array_rejects_a_number() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const t = new BigInt64Array(1); t[0] = 1;"));
    }

    @Test
    public void test_detached_method_call() {
        final var source = """
                const buffer = new ArrayBuffer(2);
                const t = new Int8Array(buffer);
                buffer.transfer();
                t.fill(1)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_length_tracking_view_follows_a_resize() {
        final var source = """
                const buffer = new ArrayBuffer(2, { maxByteLength: 8 });
                const t = new Int8Array(buffer);
                buffer.resize(4);
                t.length
                """;
        assertEquals(4, num(source));
    }

    @Test
    public void test_resize_beyond_the_maximum() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("const b = new ArrayBuffer(2, { maxByteLength: 4 }); b.resize(8)"));
    }

    @Test
    public void test_fixed_buffer_cannot_resize() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const b = new ArrayBuffer(2); b.resize(4)"));
    }

    @Test
    public void test_transfer_to_fixed_length() {
        final var source = """
                const buffer = new ArrayBuffer(2, { maxByteLength: 4 });
                const fixed = buffer.transferToFixedLength();
                String(fixed.resizable) + ':' + String(buffer.detached)
                """;
        assertEquals("false:true", str(source));
    }

    @Test
    public void test_detached_buffer_slice() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const b = new ArrayBuffer(2); b.transfer(); b.slice(0)"));
    }

    @Test
    public void test_view_length_beyond_the_buffer() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int16Array(new ArrayBuffer(4), 0, 5)"));
    }
}
