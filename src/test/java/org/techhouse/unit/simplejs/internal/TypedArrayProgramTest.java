package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TypedArrayProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A typed array constructed from a length is zero-filled
    @Test
    public void test_construct_from_length() {
        assertEquals(3, num("new Int8Array(3).length"));
        assertEquals(0, num("new Int8Array(3)[0]"));
    }

    // A typed array constructed from an array-like copies the elements
    @Test
    public void test_construct_from_array() {
        assertEquals(2, num("new Uint8Array([1, 2, 3])[1]"));
    }

    // A typed array constructed over a buffer views it, and the length derives from the buffer size
    @Test
    public void test_construct_over_buffer() {
        assertEquals(2, num("const b = new ArrayBuffer(8); new Int32Array(b).length"));
        final var source = "const b = new ArrayBuffer(8); const a = new Int32Array(b); a[0] = 7; new Int32Array(b)[0]";
        assertEquals(7, num(source));
    }

    // A typed array constructed from any iterable drains it
    @Test
    public void test_construct_from_iterable() {
        assertEquals(3, num("new Uint8Array(new Set([1, 2, 3])).length"));
    }

    // for-of iterates a typed array's elements
    @Test
    public void test_for_of_iteration() {
        assertEquals(6, num("let s = 0; for (const x of new Uint8Array([1, 2, 3])) s += x; s"));
    }

    // Spread expands a typed array into an array literal
    @Test
    public void test_spread() {
        assertEquals("1,2,3", str("[...new Uint8Array([1, 2, 3])].join(',')"));
    }

    // map returns a same-kind typed array; reduce folds over the elements
    @Test
    public void test_map_and_reduce() {
        assertEquals("2,4,6", str("new Uint8Array([1, 2, 3]).map(x => x * 2).join(',')"));
        assertEquals(10, num("new Int32Array([1, 2, 3, 4]).reduce((a, b) => a + b, 0)"));
    }

    // filter keeps matching elements in a same-kind typed array
    @Test
    public void test_filter() {
        assertEquals(2, num("new Int8Array([1, 2, 3, 4]).filter(x => x % 2 === 0).length"));
    }

    // subarray shares the underlying buffer with the source
    @Test
    public void test_subarray_shares_buffer() {
        final var source = "const a = new Uint8Array([1, 2, 3, 4]); const s = a.subarray(1, 3); s[0] = 99; a[1]";
        assertEquals(99, num(source));
    }

    // slice copies into an independent typed array
    @Test
    public void test_slice_is_independent() {
        final var source = "const a = new Uint8Array([1, 2, 3, 4]); const s = a.slice(1, 3); s[0] = 99; a[1]";
        assertEquals(2, num(source));
    }

    // set copies a source array into the target at an offset
    @Test
    public void test_set() {
        assertEquals("0,5,6,0", str("const a = new Uint8Array(4); a.set([5, 6], 1); a.join(',')"));
    }

    // fill writes a value across a range
    @Test
    public void test_fill() {
        assertEquals(7, num("new Uint8Array(3).fill(7)[1]"));
    }

    // indexOf/includes/at operate on numeric elements
    @Test
    public void test_search_and_at() {
        assertEquals(1, num("new Int16Array([10, 20, 30]).indexOf(20)"));
        assertTrue(bool("new Int16Array([10, 20, 30]).includes(30)"));
        assertEquals(30, num("new Int16Array([10, 20, 30]).at(-1)"));
    }

    // Uint8ClampedArray clamps out-of-range writes on construction
    @Test
    public void test_clamped_construction() {
        assertEquals(255, num("new Uint8ClampedArray([300])[0]"));
    }

    // A DataView round-trips integers and floats with explicit endianness
    @Test
    public void test_data_view_roundtrip() {
        assertEquals(-5,
                num("const dv = new DataView(new ArrayBuffer(8)); dv.setInt32(0, -5, true); dv.getInt32(0, true)"));
        assertEquals(1.5, num("const dv = new DataView(new ArrayBuffer(8)); dv.setFloat64(0, 1.5); dv.getFloat64(0)"));
    }

    // BigInt64Array stores and returns JsBigInt values
    @Test
    public void test_bigint64_array() {
        final var value = Interpreter.run("const a = new BigInt64Array(1); a[0] = 9007199254740993n; a[0]");
        assertEquals(new BigInteger("9007199254740993"), ((JsBigInt) value).getValue());
    }

    // Geometry and static properties are exposed
    @Test
    public void test_geometry_and_statics() {
        assertEquals(16, num("new Float64Array(2).byteLength"));
        assertEquals(4, num("Int32Array.BYTES_PER_ELEMENT"));
        assertTrue(bool("ArrayBuffer.isView(new Int8Array(1))"));
        assertEquals(2, num("Array.from(new Uint8Array([1, 2])).length"));
    }

    // A typed array view exposes the buffer it wraps
    @Test
    public void test_buffer_accessor() {
        assertEquals(8, num("new Int32Array(2).buffer.byteLength"));
    }

    // forEach visits every element with its index
    @Test
    public void test_for_each() {
        assertEquals("0:10,1:20",
                str("let out = []; new Int8Array([10, 20]).forEach((v, i) => out.push(i + ':' + v)); out.join(',')"));
    }

    // reduceRight folds from the right; reduce without an initial value seeds from the first element
    @Test
    public void test_reduce_variants() {
        assertEquals("4321", str("new Int8Array([1, 2, 3, 4]).reduceRight((a, b) => a + '' + b, '')"));
        assertEquals(10, num("new Int8Array([1, 2, 3, 4]).reduce((a, b) => a + b)"));
    }

    // find/findIndex locate the first matching element
    @Test
    public void test_find() {
        assertEquals(20, num("new Int16Array([10, 20, 30]).find(x => x > 15)"));
        assertEquals(2, num("new Int16Array([10, 20, 30]).findIndex(x => x > 25)"));
        assertEquals(-1, num("new Int16Array([10, 20, 30]).findIndex(x => x > 99)"));
    }

    // some/every evaluate a predicate across the elements
    @Test
    public void test_some_every() {
        assertTrue(bool("new Int8Array([1, 2, 3]).some(x => x === 2)"));
        assertTrue(bool("new Int8Array([2, 4, 6]).every(x => x % 2 === 0)"));
        assertFalse(bool("new Int8Array([2, 3, 6]).every(x => x % 2 === 0)"));
    }

    // lastIndexOf scans from the end; includes reports absence
    @Test
    public void test_last_index_and_missing() {
        assertEquals(3, num("new Int8Array([1, 2, 1, 2]).lastIndexOf(2)"));
        assertFalse(bool("new Int8Array([1, 2, 3]).includes(9)"));
        assertEquals(-1, num("new Int8Array([1, 2, 3]).indexOf(9)"));
    }

    // join accepts a custom separator; toString comma-joins
    @Test
    public void test_join_and_to_string() {
        assertEquals("1-2-3", str("new Int8Array([1, 2, 3]).join('-')"));
        assertEquals("1,2,3", str("new Int8Array([1, 2, 3]).toString()"));
    }

    // reverse mutates in place
    @Test
    public void test_reverse() {
        assertEquals("3,2,1", str("new Int8Array([1, 2, 3]).reverse().join(',')"));
    }

    // keys/values/entries return iterator objects
    @Test
    public void test_iterators() {
        assertEquals("0,1,2", str("[...new Int8Array([9, 8, 7]).keys()].join(',')"));
        assertEquals("9,8,7", str("[...new Int8Array([9, 8, 7]).values()].join(',')"));
        assertEquals(12, num("let n = 0; for (const [i, v] of new Int8Array([5, 6]).entries()) n += i + v; n"));
    }

    // from applies a map function; of builds from arguments
    @Test
    public void test_from_and_of() {
        assertEquals("2,4,6", str("Int8Array.from([1, 2, 3], x => x * 2).join(',')"));
        assertEquals("1,2,3", str("Int8Array.of(1, 2, 3).join(',')"));
        assertEquals(3, num("Uint8Array.from(new Set([1, 2, 3])).length"));
    }

    // A typed array can be constructed from another typed array (kind conversion)
    @Test
    public void test_construct_from_typed_array() {
        assertEquals("1,2,3", str("new Int32Array(new Uint8Array([1, 2, 3])).join(',')"));
    }

    // A typed array over a buffer honours an explicit offset and length
    @Test
    public void test_construct_over_buffer_with_offset() {
        final var source = "const b = new ArrayBuffer(16); const a = new Int32Array(b, 4, 2); a.byteOffset + ',' + a.length";
        assertEquals("4,2", str(source));
    }

    // ArrayBuffer.slice copies a byte range
    @Test
    public void test_array_buffer_slice() {
        final var source = "const a = new Uint8Array([1, 2, 3, 4]); const b = a.buffer.slice(1, 3); new Uint8Array(b).join(',')";
        assertEquals("2,3", str(source));
    }

    // DataView exposes every width and both endiannesses
    @Test
    public void test_data_view_widths() {
        assertEquals(127, num("const d = new DataView(new ArrayBuffer(8)); d.setInt8(0, 127); d.getInt8(0)"));
        assertEquals(255, num("const d = new DataView(new ArrayBuffer(8)); d.setUint8(0, 255); d.getUint8(0)"));
        assertEquals(-2,
                num("const d = new DataView(new ArrayBuffer(8)); d.setInt16(0, -2, true); d.getInt16(0, true)"));
        assertEquals(65535,
                num("const d = new DataView(new ArrayBuffer(8)); d.setUint16(0, 65535, true); d.getUint16(0, true)"));
        assertEquals(4294967295.0, num(
                "const d = new DataView(new ArrayBuffer(8)); d.setUint32(0, 4294967295, true); d.getUint32(0, true)"));
        assertEquals(0.5,
                num("const d = new DataView(new ArrayBuffer(8)); d.setFloat32(0, 0.5, true); d.getFloat32(0, true)"));
    }

    // DataView round-trips BigInt64/BigUint64
    @Test
    public void test_data_view_bigint() {
        final var signed = Interpreter
                .run("const d = new DataView(new ArrayBuffer(8)); d.setBigInt64(0, -1n, true); d.getBigInt64(0, true)");
        assertEquals(new BigInteger("-1"), ((JsBigInt) signed).getValue());
        final var unsigned = Interpreter.run(
                "const d = new DataView(new ArrayBuffer(8)); d.setBigUint64(0, -1n, true); d.getBigUint64(0, true)");
        assertEquals(new BigInteger("18446744073709551615"), ((JsBigInt) unsigned).getValue());
    }

    // DataView geometry accessors are exposed
    @Test
    public void test_data_view_geometry() {
        final var source = "const b = new ArrayBuffer(16); const d = new DataView(b, 4, 8); d.byteOffset + ',' + d.byteLength + ',' + (d.buffer === b)";
        assertEquals("4,8,true", str(source));
    }

    // Constructing a DataView without a buffer throws a catchable TypeError
    @Test
    public void test_data_view_requires_buffer() {
        assertEquals("TypeError", str("let n; try { new DataView(5); } catch (e) { n = e.name } n"));
    }

    // An out-of-range typed-array offset throws a catchable RangeError
    @Test
    public void test_bad_offset_throws() {
        assertEquals("RangeError",
                str("let n; try { new Int32Array(new ArrayBuffer(8), 3); } catch (e) { n = e.name } n"));
    }

    // set rejects a source that overflows the target
    @Test
    public void test_set_overflow_throws() {
        assertEquals("RangeError", str("let n; try { new Uint8Array(2).set([1, 2, 3]); } catch (e) { n = e.name } n"));
    }

    // A callback-less iteration method throws a catchable TypeError
    @Test
    public void test_missing_callback_throws() {
        assertEquals("TypeError", str("let n; try { new Int8Array([1]).map(); } catch (e) { n = e.name } n"));
    }

    // JSON.stringify emits a typed array as a JSON array of its elements
    @Test
    public void test_json_stringify() {
        assertEquals("[1,2,3]", str("JSON.stringify(new Uint8Array([1, 2, 3]))"));
        assertEquals("{}", str("JSON.stringify(new ArrayBuffer(4))"));
        assertEquals("{}", str("JSON.stringify(new DataView(new ArrayBuffer(4)))"));
    }

    // indexOf/includes compare BigInt elements by value
    @Test
    public void test_bigint_search() {
        assertEquals(1, num("new BigInt64Array([1n, 2n, 3n]).indexOf(2n)"));
        assertTrue(bool("new BigInt64Array([1n, 2n]).includes(2n)"));
        assertFalse(bool("new BigInt64Array([1n, 2n]).includes(5n)"));
    }

    // set copies from another typed array; a non-array-like source is a no-op
    @Test
    public void test_set_variants() {
        assertEquals("1,2,0", str("const a = new Uint8Array(3); a.set(new Uint8Array([1, 2])); a.join(',')"));
        assertEquals("0,0", str("const a = new Uint8Array(2); a.set(5); a.join(',')"));
    }

    // fill honours explicit start/end bounds
    @Test
    public void test_fill_range() {
        assertEquals("0,7,7,0", str("new Uint8Array(4).fill(7, 1, 3).join(',')"));
    }

    // slice and subarray accept negative indices
    @Test
    public void test_negative_indices() {
        assertEquals("3,4", str("new Uint8Array([1, 2, 3, 4]).slice(-2).join(',')"));
        assertEquals("2,3", str("new Uint8Array([1, 2, 3, 4]).subarray(-3, -1).join(',')"));
    }

    // reduce over an empty typed array with no seed throws a catchable TypeError
    @Test
    public void test_reduce_empty_throws() {
        assertEquals("TypeError",
                str("let n; try { new Int8Array(0).reduce((a, b) => a + b); } catch (e) { n = e.name } n"));
    }

    // A DataView BigInt setter rejects a non-BigInt value
    @Test
    public void test_data_view_bigint_rejects_number() {
        assertEquals("TypeError",
                str("let n; try { new DataView(new ArrayBuffer(8)).setBigInt64(0, 1); } catch (e) { n = e.name } n"));
    }

    // A misaligned buffer length for the element size throws a catchable RangeError
    @Test
    public void test_misaligned_buffer_throws() {
        assertEquals("RangeError",
                str("let n; try { new Int32Array(new ArrayBuffer(6)); } catch (e) { n = e.name } n"));
        assertEquals("RangeError",
                str("let n; try { new Int32Array(new ArrayBuffer(8), 0, 5); } catch (e) { n = e.name } n"));
    }

    // A DataView length beyond the buffer throws a catchable RangeError
    @Test
    public void test_data_view_out_of_range_throws() {
        assertEquals("RangeError",
                str("let n; try { new DataView(new ArrayBuffer(4), 0, 8); } catch (e) { n = e.name } n"));
    }

    // An unknown member on a typed array or DataView reads undefined
    @Test
    public void test_unknown_members() {
        assertTrue(bool("new Int8Array(1).nope === undefined"));
        assertTrue(bool("new DataView(new ArrayBuffer(4)).nope === undefined"));
        assertTrue(bool("new ArrayBuffer(4).nope === undefined"));
    }
}
