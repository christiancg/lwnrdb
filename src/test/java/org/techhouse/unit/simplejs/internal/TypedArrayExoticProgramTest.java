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

    // A view over a buffer honours the byte offset and length
    @Test
    public void test_construct_over_a_buffer() {
        assertEquals(2, num("new Int8Array(new ArrayBuffer(4), 1, 2).length"));
    }

    // Omitting the length takes the rest of the buffer
    @Test
    public void test_construct_over_a_buffer_without_length() {
        assertEquals(2, num("new Int8Array(new ArrayBuffer(4), 2).length"));
    }

    // An offset past the end of the buffer is a RangeError
    @Test
    public void test_construct_rejects_an_offset_past_the_buffer() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(new ArrayBuffer(4), 8)"));
    }

    // A detached buffer cannot back a new view
    @Test
    public void test_construct_rejects_a_detached_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4);
                buffer.transfer();
                new Int8Array(buffer)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // No argument builds an empty typed array
    @Test
    public void test_construct_without_arguments() {
        assertEquals(0, num("new Int8Array().length"));
    }

    // A negative length is a RangeError
    @Test
    public void test_construct_rejects_a_negative_length() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(-1)"));
    }

    // A DataView over a detached buffer is a TypeError
    @Test
    public void test_data_view_rejects_a_detached_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4);
                buffer.transfer();
                new DataView(buffer)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // A DataView offset past the end of the buffer is a RangeError
    @Test
    public void test_data_view_rejects_an_offset_past_the_buffer() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DataView(new ArrayBuffer(4), 8)"));
    }

    // A DataView longer than the remaining buffer is a RangeError
    @Test
    public void test_data_view_rejects_an_oversized_length() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DataView(new ArrayBuffer(4), 0, 8)"));
    }

    // A DataView reports the byte length it was given
    @Test
    public void test_data_view_byte_length() {
        assertEquals(2, num("new DataView(new ArrayBuffer(4), 1, 2).byteLength"));
    }

    // Half-precision values round-trip through a DataView
    @Test
    public void test_data_view_float16_round_trip() {
        final var source = """
                const view = new DataView(new ArrayBuffer(2));
                view.setFloat16(0, 1.5);
                view.getFloat16(0)
                """;
        assertEquals(1.5, num(source));
    }

    // subarray shares the buffer with the original view
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

    // subarray honours an explicit end and a negative start
    @Test
    public void test_subarray_bounds() {
        assertEquals("2,3", str("new Int8Array([1, 2, 3, 4]).subarray(1, 3).join(',')"));
        assertEquals("3,4", str("new Int8Array([1, 2, 3, 4]).subarray(-2).join(',')"));
    }

    // slice copies instead of sharing
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

    // The species constructor decides the kind of the result of map
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

    // filter and slice consult the species constructor too
    @Test
    public void test_filter_and_slice_use_the_species_constructor() {
        final var source = """
                const t = new Int8Array([1, 2, 3]);
                t.constructor = { [Symbol.species]: Int16Array };
                String(t.filter(x => x > 1) instanceof Int16Array) + ',' + String(t.slice(1) instanceof Int16Array)
                """;
        assertEquals("true,true", str(source));
    }

    // A null species falls back to the default constructor
    @Test
    public void test_null_species_falls_back_to_the_default() {
        final var source = """
                const t = new Int8Array([1, 2]);
                t.constructor = { [Symbol.species]: null };
                t.map(x => x * 2).join(',')
                """;
        assertEquals("2,4", str(source));
    }

    // An undefined constructor property falls back to the default constructor
    @Test
    public void test_undefined_constructor_falls_back_to_the_default() {
        final var source = """
                const t = new Int8Array([1, 2]);
                t.constructor = undefined;
                t.map(x => x * 2).join(',')
                """;
        assertEquals("2,4", str(source));
    }

    // A primitive constructor property is a TypeError
    @Test
    public void test_primitive_constructor_property_is_rejected() {
        final var source = """
                const t = new Int8Array([1]);
                t.constructor = 5;
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // A non-callable species is a TypeError
    @Test
    public void test_non_callable_species_is_rejected() {
        final var source = """
                const t = new Int8Array([1]);
                t.constructor = { [Symbol.species]: 5 };
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // A species that does not return a typed array is a TypeError
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

    // A species returning a shorter typed array is a TypeError
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

    // A BigInt species for a number array is a TypeError
    @Test
    public void test_species_cannot_change_the_content_type() {
        final var source = """
                const t = new Int8Array([1, 2]);
                t.constructor = { [Symbol.species]: BigInt64Array };
                t.map(x => x)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // toLocaleString joins the localized elements with commas
    @Test
    public void test_to_locale_string() {
        assertEquals("1,2,3", str("new Int8Array([1, 2, 3]).toLocaleString()"));
    }

    // toLocaleString of an empty typed array is the empty string
    @Test
    public void test_to_locale_string_of_an_empty_array() {
        assertEquals("", str("new Int8Array(0).toLocaleString()"));
    }

    // toLocaleString works over BigInt elements
    @Test
    public void test_to_locale_string_of_bigints() {
        assertEquals("1,2", str("new BigInt64Array([1n, 2n]).toLocaleString()"));
    }

    // from applies its mapper and constructs the receiver's kind
    @Test
    public void test_from_with_a_mapper() {
        final var source = """
                const t = Int8Array.from([1, 2, 3], x => x * 2);
                t.join(',') + ':' + String(t instanceof Int8Array)
                """;
        assertEquals("2,4,6:true", str(source));
    }

    // from called with a custom constructor passes it the source length
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

    // from called on a non-constructor is a TypeError
    @Test
    public void test_from_rejects_a_primitive_this() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Int8Array.from.call(5, [1])"));
    }

    // of called on a non-constructor is a TypeError
    @Test
    public void test_of_rejects_a_primitive_this() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Int8Array.of.call(5, 1)"));
    }

    // from iterates an iterable source
    @Test
    public void test_from_an_iterable() {
        assertEquals("1,2", str("Int8Array.from(new Set([1, 2])).join(',')"));
    }

    // set copies from another typed array at the given offset
    @Test
    public void test_set_from_a_typed_array() {
        assertEquals("0,1,2,0", str("const t = new Int8Array(4); t.set(new Int8Array([1, 2]), 1); t.join(',')"));
    }

    // set copies from an array-like at the given offset
    @Test
    public void test_set_from_an_array_like() {
        assertEquals("0,7,8", str("const t = new Int8Array(3); t.set([7, 8], 1); t.join(',')"));
    }

    // A source that does not fit at the offset is a RangeError
    @Test
    public void test_set_rejects_an_oversized_source() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(2).set([1, 2], 2)"));
    }

    // A negative offset is a RangeError
    @Test
    public void test_set_rejects_a_negative_offset() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(2).set([1], -1)"));
    }

    // A comparator that is neither undefined nor callable is a TypeError
    @Test
    public void test_sort_rejects_a_non_callable_comparator() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array([2, 1]).sort(5)"));
    }

    // A comparator returning NaN is treated as zero, leaving the order alone
    @Test
    public void test_sort_treats_nan_as_zero() {
        assertEquals("2,1", str("new Int8Array([2, 1]).sort(() => NaN).join(',')"));
    }

    // lastIndexOf accepts a negative start
    @Test
    public void test_last_index_of_with_a_negative_start() {
        assertEquals(2, num("new Int8Array([1, 2, 1]).lastIndexOf(1, -1)"));
    }

    // lastIndexOf over an empty typed array is -1
    @Test
    public void test_last_index_of_on_an_empty_array() {
        assertEquals(-1, num("new Int8Array(0).lastIndexOf(1)"));
    }

    // indexOf accepts a negative start
    @Test
    public void test_index_of_with_a_negative_start() {
        assertEquals(2, num("new Int8Array([1, 2, 3]).indexOf(3, -1)"));
    }

    // includes uses SameValueZero, so it finds NaN
    @Test
    public void test_includes_finds_nan() {
        assertTrue(bool());
    }

    // includes over an empty typed array is false
    @Test
    public void test_includes_on_an_empty_array() {
        assertEquals("false", str("String(new Int8Array(0).includes(1))"));
    }

    // find reports undefined and findIndex -1 when nothing matches
    @Test
    public void test_find_without_a_match() {
        assertEquals("undefined,-1",
                str("String(new Int8Array([1]).find(x => x > 5)) + ',' + new Int8Array([1]).findIndex(x => x > 5)"));
    }

    // findLast and findLastIndex scan from the end
    @Test
    public void test_find_last() {
        assertEquals("2,1", str(
                "String(new Int8Array([1, 2, 3]).findLast(x => x < 3)) + ',' + new Int8Array([1, 2, 3]).findLastIndex(x => x < 3)"));
    }

    // reduceRight folds from the end
    @Test
    public void test_reduce_right() {
        assertEquals("321", str("new Int8Array([1, 2, 3]).reduceRight((a, b) => a + '' + b)"));
    }

    // reduce over an empty typed array with no seed is a TypeError
    @Test
    public void test_reduce_rejects_an_empty_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array(0).reduce((a, b) => a + b)"));
    }

    // copyWithin moves elements inside the same view
    @Test
    public void test_copy_within() {
        assertEquals("3,4,3,4", str("new Int8Array([1, 2, 3, 4]).copyWithin(0, 2).join(',')"));
    }

    // with returns a copy carrying the replaced element
    @Test
    public void test_with_replaces_an_element() {
        assertEquals("9,2", str("new Int8Array([1, 2]).with(0, 9).join(',')"));
    }

    // with rejects an out-of-range index
    @Test
    public void test_with_rejects_an_out_of_range_index() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array([1]).with(5, 1)"));
    }

    // toSorted and toReversed leave the receiver untouched
    @Test
    public void test_by_copy_methods() {
        final var source = """
                const t = new Int8Array([3, 1]);
                t.toSorted().join(',') + ':' + t.toReversed().join(',') + ':' + t.join(',')
                """;
        assertEquals("1,3:1,3:3,1", str(source));
    }

    // keys, values and entries iterate the view
    @Test
    public void test_iteration_helpers() {
        final var source = """
                const t = new Int8Array([5, 6]);
                [...t.keys()].join('') + ':' + [...t.values()].join('') + ':'
                        + [...t.entries()].map(e => e.join('-')).join(',')
                """;
        assertEquals("01:56:0-5,1-6", str(source));
    }

    // at accepts a negative index and reports undefined out of range
    @Test
    public void test_at() {
        assertEquals("2,undefined",
                str("String(new Int8Array([1, 2]).at(-1)) + ',' + String(new Int8Array([1]).at(5))"));
    }

    // Uint8ClampedArray clamps out-of-range writes
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

    // A BigUint64Array write wraps modulo 2^64
    @Test
    public void test_big_uint64_wraps() {
        assertEquals("18446744073709551615", str("const t = new BigUint64Array(1); t[0] = -1n; String(t[0])"));
    }

    // A number written into a BigInt array is a TypeError
    @Test
    public void test_bigint_array_rejects_a_number() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const t = new BigInt64Array(1); t[0] = 1;"));
    }

    // A write to a non-canonical index string creates an ordinary property
    @Test
    public void test_non_canonical_index_write_is_ordinary() {
        final var source = """
                const t = new Int8Array(2);
                t['1.0'] = 5;
                String(t['1.0']) + ':' + t[1]
                """;
        assertEquals("5:0", str(source));
    }

    // A write past the end of the view is dropped
    @Test
    public void test_out_of_range_write_is_dropped() {
        assertEquals("undefined", str("const t = new Int8Array(1); t[5] = 3; String(t[5])"));
    }

    // defineProperty on a valid index writes through to the element
    @Test
    public void test_define_property_on_an_index() {
        final var source = """
                const t = new Int8Array(1);
                Object.defineProperty(t, '0', { value: 7 });
                t[0]
                """;
        assertEquals(7, num(source));
    }

    // Reading an index of a detached view is undefined
    @Test
    public void test_detached_index_read() {
        final var source = """
                const buffer = new ArrayBuffer(2);
                const t = new Int8Array(buffer);
                buffer.transfer();
                String(t[0])
                """;
        assertEquals("undefined", str(source));
    }

    // A method call on a detached view is a TypeError
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

    // A length-tracking view follows a resizable buffer as it grows
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

    // A fixed-length view that falls outside a shrunken buffer reports zero length
    @Test
    public void test_view_out_of_bounds_after_shrink() {
        final var source = """
                const buffer = new ArrayBuffer(8, { maxByteLength: 8 });
                const t = new Int8Array(buffer, 4, 2);
                buffer.resize(2);
                t.length
                """;
        assertEquals(0, num(source));
    }

    // Resizing beyond maxByteLength is a RangeError
    @Test
    public void test_resize_beyond_the_maximum() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("const b = new ArrayBuffer(2, { maxByteLength: 4 }); b.resize(8)"));
    }

    // A fixed-length buffer cannot be resized
    @Test
    public void test_fixed_buffer_cannot_resize() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("const b = new ArrayBuffer(2); b.resize(4)"));
    }

    // transferToFixedLength detaches the source and drops resizability
    @Test
    public void test_transfer_to_fixed_length() {
        final var source = """
                const buffer = new ArrayBuffer(2, { maxByteLength: 4 });
                const fixed = buffer.transferToFixedLength();
                String(fixed.resizable) + ':' + String(buffer.detached)
                """;
        assertEquals("false:true", str(source));
    }

    // slice on a detached buffer is a TypeError
    @Test
    public void test_detached_buffer_slice() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const b = new ArrayBuffer(2); b.transfer(); b.slice(0)"));
    }

    // A view length beyond the buffer is a RangeError
    @Test
    public void test_view_length_beyond_the_buffer() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int16Array(new ArrayBuffer(4), 0, 5)"));
    }

    // An offset past a resizable buffer is a RangeError
    @Test
    public void test_offset_past_a_resizable_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4, { maxByteLength: 8 });
                new Int8Array(buffer, 8)
                """;
        assertThrows(RangeErrorException.class, () -> Interpreter.run(source));
    }

    // A DataView offset past a resizable buffer is a RangeError
    @Test
    public void test_data_view_offset_past_a_resizable_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4, { maxByteLength: 8 });
                new DataView(buffer, 8)
                """;
        assertThrows(RangeErrorException.class, () -> Interpreter.run(source));
    }

    // A DataView over a detached resizable buffer is a TypeError
    @Test
    public void test_data_view_over_a_detached_resizable_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4, { maxByteLength: 8 });
                buffer.transfer();
                new DataView(buffer, 0)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // includes and indexOf with no argument search for undefined
    @Test
    public void test_search_methods_without_an_argument() {
        assertEquals("false:-1", str("String(new Int8Array([1]).includes()) + ':' + new Int8Array([1]).indexOf()"));
    }

    // set over a BigInt array coerces through the BigInt path
    @Test
    public void test_set_over_a_bigint_array() {
        assertEquals("1", str("const t = new BigInt64Array(1); t.set([1n]); String(t[0])"));
    }

    // A view reports the byte offset it was constructed with
    @Test
    public void test_byte_offset_accessor() {
        assertEquals(2, num("new Int8Array(new ArrayBuffer(4), 2, 1).byteOffset"));
    }
}
