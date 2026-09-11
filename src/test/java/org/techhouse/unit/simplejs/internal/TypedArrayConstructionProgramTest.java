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

public class TypedArrayConstructionProgramTest {
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

    // subarray shares the underlying buffer with the source
    @Test
    public void test_subarray_shares_buffer() {
        final var source = "const a = new Uint8Array([1, 2, 3, 4]); const s = a.subarray(1, 3); s[0] = 99; a[1]";
        assertEquals(99, num(source));
    }

    // Uint8ClampedArray clamps out-of-range writes on construction
    @Test
    public void test_clamped_construction() {
        assertEquals(255, num("new Uint8ClampedArray([300])[0]"));
    }

    // BigInt64Array stores and returns JsBigInt values
    @Test
    public void test_bigint64_array() {
        final var value = Interpreter.run("const a = new BigInt64Array(1); a[0] = 9007199254740993n; a[0]");
        assertEquals(new BigInteger("9007199254740993"), ((JsBigInt) value).getValue());
    }

    // A typed array view exposes the buffer it wraps
    @Test
    public void test_buffer_accessor() {
        assertEquals(8, num("new Int32Array(2).buffer.byteLength"));
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

    // indexOf/includes compare BigInt elements by value
    @Test
    public void test_bigint_search() {
        assertEquals(1, num("new BigInt64Array([1n, 2n, 3n]).indexOf(2n)"));
        assertTrue(bool("new BigInt64Array([1n, 2n]).includes(2n)"));
        assertFalse(bool("new BigInt64Array([1n, 2n]).includes(5n)"));
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

    // Float16Array round-trips exact half-precision values and quantizes others
    @Test
    public void test_float16_array() {
        assertEquals(1.5, num("new Float16Array([1.5, 2.25])[0]"));
        assertEquals(2.25, num("new Float16Array([1.5, 2.25])[1]"));
        assertEquals(2, num("Float16Array.BYTES_PER_ELEMENT"));
        assertEquals(1, num("new Float16Array(4).length - 3"));
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
}
