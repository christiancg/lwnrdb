package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
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

    // A typed array constructed from a plain array-like object (no Symbol.iterator) falls back to
    // array-like semantics instead of throwing "is not iterable"
    @Test
    public void test_construct_from_array_like_object() {
        assertEquals(2, num("new Uint8Array({length: 3, 0: 1, 1: 2, 2: 3})[1]"));
        assertEquals(0, num("new Uint8Array({length: 0}).length"));
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

    // Every concrete typed array constructor's own [[Prototype]] is the shared abstract
    // %TypedArray% intrinsic (Object.getPrototypeOf(Int8Array)), matching what test262's
    // testTypedArray.js harness (`var TypedArray = Object.getPrototypeOf(Int8Array)`) relies on
    @Test
    public void test_shared_typed_array_intrinsic() {
        final var source = """
                let TypedArray = Object.getPrototypeOf(Int8Array);
                let sameForEveryKind = TypedArray === Object.getPrototypeOf(Uint8Array)
                    && TypedArray === Object.getPrototypeOf(Float64Array);
                let protoLinked = Object.getPrototypeOf(Int8Array.prototype) === TypedArray.prototype;
                let iteratorIsValues = TypedArray.prototype[Symbol.iterator] === TypedArray.prototype.values;
                let notDirectlyConstructable;
                try { new TypedArray(); notDirectlyConstructable = false; }
                catch (e) { notDirectlyConstructable = e instanceof TypeError; }
                JSON.stringify([sameForEveryKind, protoLinked, iteratorIsValues, notDirectlyConstructable])
                """;
        assertEquals("[true,true,true,true]", str(source));
    }

    // TypedArray.prototype's geometry accessors (length/byteLength/byteOffset/buffer) throw when
    // invoked with a non-typed-array receiver instead of silently reading through as undefined
    @Test
    public void test_typed_array_geometry_accessor_rejects_wrong_receiver() {
        final var source = """
                let TypedArrayPrototype = Object.getPrototypeOf(Int8Array).prototype;
                let names = ['length', 'byteLength', 'byteOffset', 'buffer'];
                let results = names.map(name => {
                    try { TypedArrayPrototype[name]; return 'no-throw'; }
                    catch (e) { return e instanceof TypeError; }
                });
                JSON.stringify(results)
                """;
        assertEquals("[true,true,true,true]", str(source));
    }

    // A typed array's [[Get]] on a canonical-numeric-index-string key that isn't a valid index
    // (non-integer, negative, out of range) returns undefined directly rather than falling through
    // to a poisoned property on the shared TypedArray.prototype
    @Test
    public void test_typed_array_non_integer_numeric_key_bypasses_prototype() {
        final var source = """
                let TypedArrayPrototype = Object.getPrototypeOf(Int8Array).prototype;
                Object.defineProperty(TypedArrayPrototype, '1.5', { get() { throw new Error('should not run'); } });
                let a = new Int8Array([1, 2, 3]);
                typeof a['1.5']
                """;
        assertEquals("undefined", str(source));
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

    // A DataView accessor with an out-of-range byteOffset throws a catchable RangeError rather
    // than letting a raw ByteBuffer exception (e.g. IndexOutOfBoundsException) escape - a huge
    // offset must not silently overflow when narrowed to an int bounds check
    @Test
    public void test_data_view_out_of_range_offset_throws_range_error() {
        assertEquals("RangeError", str(
                "let n; try { new DataView(new ArrayBuffer(8)).getInt8(100000000000); } catch (e) { n = e.name } n"));
        assertEquals("RangeError",
                str("let n; try { new DataView(new ArrayBuffer(8)).getBigInt64(-1); } catch (e) { n = e.name } n"));
        assertEquals("RangeError", str(
                "let n; try { new DataView(new ArrayBuffer(8)).setInt8(100000000000, 1); } catch (e) { n = e.name } n"));
    }

    // Accessing a DataView after its buffer is detached throws a catchable TypeError rather than
    // a raw exception from reading the now-empty backing byte array
    @Test
    public void test_data_view_detached_buffer_throws_type_error() {
        final var source = """
                let n;
                const b = new ArrayBuffer(8);
                const d = new DataView(b);
                b.transfer();
                try { d.getInt8(0); } catch (e) { n = e.name; }
                n
                """;
        assertEquals("TypeError", str(source));
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

    // A non-callable predicate throws immediately, even on an empty typed array
    @Test
    public void test_non_callable_callback_throws_even_on_empty_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array(0).find(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array([1, 2]).find(null)"));
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

    // Float16Array round-trips exact half-precision values and quantizes others
    @Test
    public void test_float16_array() {
        assertEquals(1.5, num("new Float16Array([1.5, 2.25])[0]"));
        assertEquals(2.25, num("new Float16Array([1.5, 2.25])[1]"));
        assertEquals(2, num("Float16Array.BYTES_PER_ELEMENT"));
        assertEquals(1, num("new Float16Array(4).length - 3"));
    }

    // Math.f16round quantizes to half precision
    @Test
    public void test_math_f16round() {
        assertEquals(num("new Float16Array([1.337])[0]"), num("Math.f16round(1.337)"));
    }

    // DataView getFloat16/setFloat16 round-trip with explicit endianness
    @Test
    public void test_dataview_float16() {
        final var source = """
                const dv = new DataView(new ArrayBuffer(8));
                dv.setFloat16(0, 1.5, true);
                dv.getFloat16(0, true)
                """;
        assertEquals(1.5, num(source));
    }

    // a resizable ArrayBuffer grows and shrinks within maxByteLength
    @Test
    public void test_array_buffer_resize() {
        final var source = """
                const buf = new ArrayBuffer(4, { maxByteLength: 8 });
                const before = buf.byteLength;
                buf.resize(8);
                before * 100 + buf.byteLength + (buf.resizable ? 1000 : 0) + buf.maxByteLength
                """;
        assertEquals(400 + 8 + 1000 + 8, num(source));
    }

    // resizing past maxByteLength throws a RangeError
    @Test
    public void test_array_buffer_resize_past_max_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    const buf = new ArrayBuffer(4, { maxByteLength: 8 });
                    buf.resize(16);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("RangeError", str(source));
    }

    // resizing a non-resizable buffer throws a TypeError
    @Test
    public void test_array_buffer_resize_non_resizable_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    new ArrayBuffer(4).resize(2);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // transfer detaches the source buffer
    @Test
    public void test_array_buffer_transfer_detaches() {
        final var source = """
                const buf = new ArrayBuffer(4);
                const moved = buf.transfer();
                (buf.detached ? 10 : 0) + moved.byteLength
                """;
        assertEquals(14, num(source));
    }

    // transfer with an explicit new length grows the moved buffer
    @Test
    public void test_array_buffer_transfer_explicit_length() {
        assertEquals(8, num("new ArrayBuffer(4).transfer(8).byteLength"));
        assertEquals(2, num("new ArrayBuffer(4).transferToFixedLength(2).byteLength"));
    }

    // transfer of a resizable buffer yields another resizable buffer
    @Test
    public void test_array_buffer_transfer_keeps_resizable() {
        final var source = """
                const buf = new ArrayBuffer(4, { maxByteLength: 16 });
                const moved = buf.transfer();
                (moved.resizable ? 100 : 0) + moved.maxByteLength
                """;
        assertEquals(116, num(source));
    }

    // operations on a detached buffer throw a TypeError
    @Test
    public void test_array_buffer_detached_operations_throw() {
        final var source = """
                let result = 'no throw';
                try {
                    const buf = new ArrayBuffer(4, { maxByteLength: 8 });
                    buf.transfer();
                    buf.resize(8);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // transfer of an already-detached buffer throws a TypeError
    @Test
    public void test_array_buffer_double_transfer_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    const buf = new ArrayBuffer(4);
                    buf.transfer();
                    buf.transfer();
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("TypeError", str(source));
    }

    // constructing a resizable buffer with maxByteLength below byteLength throws a RangeError
    @Test
    public void test_array_buffer_bad_max_throws() {
        final var source = """
                let result = 'no throw';
                try {
                    new ArrayBuffer(8, { maxByteLength: 4 });
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("RangeError", str(source));
    }

    // a typed array over a shrunk resizable buffer reads out-of-range indexes as undefined
    @Test
    public void test_typed_array_shrunk_buffer_bounds_safe() {
        final var source = """
                const buf = new ArrayBuffer(8, { maxByteLength: 8 });
                const view = new Int32Array(buf);
                view[1] = 42;
                buf.resize(4);
                view[1] === undefined
                """;
        assertTrue(bool(source));
    }

    // an auto-length view over a resizable buffer tracks the buffer's current length when it grows
    @Test
    public void test_auto_length_view_grows() {
        final var source = """
                const buf = new ArrayBuffer(8, { maxByteLength: 16 });
                const view = new Int32Array(buf);
                const before = view.length;
                buf.resize(16);
                before * 100 + view.length
                """;
        assertEquals(2 * 100 + 4, num(source));
    }

    // an auto-length view shrinks with the buffer
    @Test
    public void test_auto_length_view_shrinks() {
        final var source = """
                const buf = new ArrayBuffer(16, { maxByteLength: 16 });
                const view = new Int32Array(buf);
                const before = view.length;
                buf.resize(4);
                before * 100 + view.length
                """;
        assertEquals(4 * 100 + 1, num(source));
    }

    // an explicit-length view does not track buffer resizes: it stays its construction-time length
    // while it still fits, and reports zero once the buffer has shrunk out from under it
    @Test
    public void test_explicit_length_view_fixed() {
        final var stillFits = """
                const buf = new ArrayBuffer(16, { maxByteLength: 16 });
                const view = new Int32Array(buf, 0, 2);
                buf.resize(12);
                view.length
                """;
        assertEquals(2, num(stillFits));
        final var outOfBounds = """
                const buf = new ArrayBuffer(16, { maxByteLength: 16 });
                const view = new Int32Array(buf, 0, 2);
                buf.resize(4);
                view.length
                """;
        assertEquals(0, num(outOfBounds));
    }

    // a view over a non-resizable buffer keeps its construction-time length
    @Test
    public void test_non_resizable_view_fixed() {
        final var source = """
                const buf = new ArrayBuffer(16);
                const view = new Int32Array(buf);
                view.length
                """;
        assertEquals(4, num(source));
    }

    // an auto-length view's byteLength tracks the buffer too
    @Test
    public void test_auto_length_view_byte_length_tracks() {
        final var source = """
                const buf = new ArrayBuffer(8, { maxByteLength: 16 });
                const view = new Float64Array(buf);
                buf.resize(16);
                view.byteLength
                """;
        assertEquals(16, num(source));
    }

    // an auto-length DataView tracks the buffer and re-clamps reads past its current length
    @Test
    public void test_auto_length_data_view_reclamps() {
        final var source = """
                let result = 'no throw';
                const buf = new ArrayBuffer(8, { maxByteLength: 8 });
                const view = new DataView(buf);
                view.setInt32(4, 7);
                buf.resize(4);
                try {
                    view.getInt32(4);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("RangeError", str(source));
    }

    // sort orders numerically by default, not lexicographically
    @Test
    public void test_sort_is_numeric() {
        assertEquals("2,10,33", str("new Int32Array([10, 2, 33]).sort().join(',')"));
        assertEquals("33,10,2", str("new Int32Array([10, 2, 33]).sort((a, b) => b - a).join(',')"));
        assertEquals("1,2", str("new BigInt64Array([2n, 1n]).sort().join(',')"));
    }

    // The by-copy methods return same-kind copies
    @Test
    public void test_by_copy_methods() {
        assertEquals("2,10", str("const t = new Int32Array([10, 2]); const s = t.toSorted();"
                + " t.join(',') === '10,2' ? s.join(',') : 'mutated'"));
        assertEquals("2,10", str("new Int32Array([10, 2]).toReversed().join(',')"));
        assertEquals("9,2", str("new Int32Array([10, 2]).with(0, 9).join(',')"));
        assertTrue(bool("new Int32Array([1]).toSorted() instanceof Int32Array"));
    }

    // with rejects an out-of-range index
    @Test
    public void test_with_range_error() {
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("new Int8Array(2).with(5, 1)"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("new Int8Array(2).with(-5, 1)"));
        assertEquals("0,9", str("new Int8Array(2).with(-1, 9).join(',')"));
    }

    // An integer element write out of the double range wraps modulo 2^32 instead of clamping
    @Test
    public void test_int32_write_out_of_range_wraps() {
        assertEquals(0, num("const a = new Int32Array(1); a[0] = 1e300; a[0]"));
        assertEquals(1410065408, num("const a = new Int32Array(1); a[0] = 1e10; a[0]"));
        assertEquals(-1, num("const a = new Int32Array(1); a[0] = -1; a[0]"));
        assertEquals(0, num("const a = new Uint8Array(1); a[0] = 1e21; a[0]"));
        assertEquals(255, num("const a = new Uint8Array(1); a[0] = -1; a[0]"));
        assertEquals(0, num("const a = new Int16Array(1); a[0] = 9.223372036854776e18; a[0]"));
    }

    // A DataView integer write wraps the same way
    @Test
    public void test_data_view_write_out_of_range_wraps() {
        final var view = "const v = new DataView(new ArrayBuffer(8));";
        assertEquals(0, num(view + " v.setInt32(0, 1e300); v.getInt32(0)"));
        assertEquals(1410065408, num(view + " v.setInt32(0, 1e10); v.getInt32(0)"));
        assertEquals(255, num(view + " v.setUint8(0, -1); v.getUint8(0)"));
    }

    // findLast, findLastIndex and copyWithin
    @Test
    public void test_find_last_and_copy_within() {
        assertEquals(4, num("new Int8Array([1, 4, 2]).findLast(v => v > 2)"));
        assertEquals(1, num("new Int8Array([1, 4, 2]).findLastIndex(v => v > 2)"));
        assertEquals(-1, num("new Int8Array([1]).findLastIndex(v => v > 9)"));
        assertEquals("3,4,3,4", str("new Int8Array([1, 2, 3, 4]).copyWithin(0, 2).join(',')"));
        assertEquals("2,2,3", str("new Int8Array([1, 2, 3]).copyWithin(0, 1, 2).join(',')"));
    }

    // typed-array includes uses SameValueZero for NaN and signed zero
    @Test
    public void test_typed_array_includes_same_value_zero() {
        assertTrue(bool("new Float64Array([NaN]).includes(NaN)"));
        assertTrue(bool("new Float32Array([NaN]).includes(NaN)"));
        assertTrue(bool("new Float64Array([-0]).includes(0)"));
        assertFalse(bool("new Float64Array([NaN]).indexOf(NaN) >= 0"));
    }

    // BigInt element kinds compare by value
    @Test
    public void test_typed_array_includes_bigint() {
        assertTrue(bool("new BigInt64Array([1n]).includes(1n)"));
        assertFalse(bool("new BigInt64Array([1n]).includes(2n)"));
    }

    // includes honours the fromIndex argument
    @Test
    public void test_typed_array_includes_from_index() {
        assertFalse(bool("new Int8Array([1, 2]).includes(1, 1)"));
        assertTrue(bool("new Int8Array([1, 2]).includes(2, -1)"));
    }

    // base64 round-trips through the instance method and the static
    @Test
    public void test_uint8_base64_round_trip() {
        assertEquals("AQID", str("new Uint8Array([1, 2, 3]).toBase64()"));
        assertEquals("1,2,3", str("Array.from(Uint8Array.fromBase64('AQID')).join(',')"));
    }

    // the base64url alphabet and omitPadding options are honoured
    @Test
    public void test_uint8_base64_options() {
        assertEquals("--8", str("new Uint8Array([251, 239]).toBase64({alphabet: 'base64url', omitPadding: true})"));
        assertEquals("--8=", str("new Uint8Array([251, 239]).toBase64({alphabet: 'base64url'})"));
        assertEquals("251,239", str("Array.from(Uint8Array.fromBase64('--8=', {alphabet: 'base64url'})).join(',')"));
    }

    // an unknown alphabet is rejected
    @Test
    public void test_uint8_base64_unknown_alphabet_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Uint8Array(1).toBase64({alphabet: 'x'})"));
    }

    // hex round-trips in lowercase
    @Test
    public void test_uint8_hex_round_trip() {
        assertEquals("0aff", str("new Uint8Array([10, 255]).toHex()"));
        assertEquals("10,255", str("Array.from(Uint8Array.fromHex('0AFF')).join(',')"));
    }

    // malformed hex and base64 input are SyntaxErrors
    @Test
    public void test_uint8_malformed_input_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('abc')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('zz')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('!!!!')"));
    }

    // setFrom* reports how much was read and written, bounded by the target
    @Test
    public void test_uint8_set_from_reports_progress() {
        assertEquals("1,2,3|4|3", str("""
                const u = new Uint8Array(3);
                const r = u.setFromBase64('AQID');
                Array.from(u).join(',') + '|' + r.read + '|' + r.written
                """));
        assertEquals("1,2|4|2", str("""
                const u = new Uint8Array(2);
                const r = u.setFromHex('010203');
                Array.from(u).join(',') + '|' + r.read + '|' + r.written
                """));
    }

    // the family is Uint8Array-only
    @Test
    public void test_base64_is_uint8_only() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Uint16Array(1).toBase64()"));
        assertTrue(bool("typeof Uint8Array.fromBase64 === 'function'"));
        assertTrue(bool("Uint16Array.fromBase64 === undefined"));
    }

    // An own "length"/"byteLength"/"byteOffset"/"buffer"/"BYTES_PER_ELEMENT" property installed via
    // Object.defineProperty shadows the exotic computed value, exactly like it would for any other
    // ordinary key - these are only "exotic" in that nothing installs them by default.
    @Test
    public void test_own_length_property_shadows_computed_length() {
        assertEquals(4000, num("""
                const ta = new Uint8Array(1);
                Object.defineProperty(ta, 'length', { value: 4000 });
                ta.length
                """));
    }

    // Without an own override the computed value still reports the real length.
    @Test
    public void test_length_without_own_override_is_computed() {
        assertEquals(3, num("new Uint8Array(3).length"));
    }
}
