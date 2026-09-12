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

    @Test
    public void test_construct_from_length() {
        assertEquals(3, num("new Int8Array(3).length"));
        assertEquals(0, num("new Int8Array(3)[0]"));
    }

    @Test
    public void test_construct_from_array() {
        assertEquals(2, num("new Uint8Array([1, 2, 3])[1]"));
    }

    @Test
    public void test_construct_from_array_like_object() {
        assertEquals(2, num("new Uint8Array({length: 3, 0: 1, 1: 2, 2: 3})[1]"));
        assertEquals(0, num("new Uint8Array({length: 0}).length"));
    }

    @Test
    public void test_construct_over_buffer() {
        assertEquals(2, num("const b = new ArrayBuffer(8); new Int32Array(b).length"));
        final var source = "const b = new ArrayBuffer(8); const a = new Int32Array(b); a[0] = 7; new Int32Array(b)[0]";
        assertEquals(7, num(source));
    }

    @Test
    public void test_construct_from_iterable() {
        assertEquals(3, num("new Uint8Array(new Set([1, 2, 3])).length"));
    }

    @Test
    public void test_for_of_iteration() {
        assertEquals(6, num("let s = 0; for (const x of new Uint8Array([1, 2, 3])) s += x; s"));
    }

    @Test
    public void test_subarray_shares_buffer() {
        final var source = "const a = new Uint8Array([1, 2, 3, 4]); const s = a.subarray(1, 3); s[0] = 99; a[1]";
        assertEquals(99, num(source));
    }

    @Test
    public void test_clamped_construction() {
        assertEquals(255, num("new Uint8ClampedArray([300])[0]"));
    }

    @Test
    public void test_bigint64_array() {
        final var value = Interpreter.run("const a = new BigInt64Array(1); a[0] = 9007199254740993n; a[0]");
        assertEquals(new BigInteger("9007199254740993"), ((JsBigInt) value).getValue());
    }

    @Test
    public void test_buffer_accessor() {
        assertEquals(8, num("new Int32Array(2).buffer.byteLength"));
    }

    @Test
    public void test_from_and_of() {
        assertEquals("2,4,6", str("Int8Array.from([1, 2, 3], x => x * 2).join(',')"));
        assertEquals("1,2,3", str("Int8Array.of(1, 2, 3).join(',')"));
        assertEquals(3, num("Uint8Array.from(new Set([1, 2, 3])).length"));
    }

    @Test
    public void test_construct_from_typed_array() {
        assertEquals("1,2,3", str("new Int32Array(new Uint8Array([1, 2, 3])).join(',')"));
    }

    @Test
    public void test_construct_over_buffer_with_offset() {
        final var source = "const b = new ArrayBuffer(16); const a = new Int32Array(b, 4, 2); a.byteOffset + ',' + a.length";
        assertEquals("4,2", str(source));
    }

    @Test
    public void test_array_buffer_slice() {
        final var source = "const a = new Uint8Array([1, 2, 3, 4]); const b = a.buffer.slice(1, 3); new Uint8Array(b).join(',')";
        assertEquals("2,3", str(source));
    }

    @Test
    public void test_data_view_bigint() {
        final var signed = Interpreter
                .run("const d = new DataView(new ArrayBuffer(8)); d.setBigInt64(0, -1n, true); d.getBigInt64(0, true)");
        assertEquals(new BigInteger("-1"), ((JsBigInt) signed).getValue());
        final var unsigned = Interpreter.run(
                "const d = new DataView(new ArrayBuffer(8)); d.setBigUint64(0, -1n, true); d.getBigUint64(0, true)");
        assertEquals(new BigInteger("18446744073709551615"), ((JsBigInt) unsigned).getValue());
    }

    @Test
    public void test_data_view_requires_buffer() {
        assertEquals("TypeError", str("let n; try { new DataView(5); } catch (e) { n = e.name } n"));
    }

    // A huge offset must not silently overflow when narrowed to an int bounds check.
    @Test
    public void test_data_view_out_of_range_offset_throws_range_error() {
        assertEquals("RangeError", str(
                "let n; try { new DataView(new ArrayBuffer(8)).getInt8(100000000000); } catch (e) { n = e.name } n"));
        assertEquals("RangeError",
                str("let n; try { new DataView(new ArrayBuffer(8)).getBigInt64(-1); } catch (e) { n = e.name } n"));
        assertEquals("RangeError", str(
                "let n; try { new DataView(new ArrayBuffer(8)).setInt8(100000000000, 1); } catch (e) { n = e.name } n"));
    }

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

    @Test
    public void test_bad_offset_throws() {
        assertEquals("RangeError",
                str("let n; try { new Int32Array(new ArrayBuffer(8), 3); } catch (e) { n = e.name } n"));
    }

    @Test
    public void test_bigint_search() {
        assertEquals(1, num("new BigInt64Array([1n, 2n, 3n]).indexOf(2n)"));
        assertTrue(bool("new BigInt64Array([1n, 2n]).includes(2n)"));
        assertFalse(bool("new BigInt64Array([1n, 2n]).includes(5n)"));
    }

    @Test
    public void test_data_view_bigint_rejects_number() {
        assertEquals("TypeError",
                str("let n; try { new DataView(new ArrayBuffer(8)).setBigInt64(0, 1); } catch (e) { n = e.name } n"));
    }

    @Test
    public void test_misaligned_buffer_throws() {
        assertEquals("RangeError",
                str("let n; try { new Int32Array(new ArrayBuffer(6)); } catch (e) { n = e.name } n"));
        assertEquals("RangeError",
                str("let n; try { new Int32Array(new ArrayBuffer(8), 0, 5); } catch (e) { n = e.name } n"));
    }

    @Test
    public void test_data_view_out_of_range_throws() {
        assertEquals("RangeError",
                str("let n; try { new DataView(new ArrayBuffer(4), 0, 8); } catch (e) { n = e.name } n"));
    }

    @Test
    public void test_float16_array() {
        assertEquals(1.5, num("new Float16Array([1.5, 2.25])[0]"));
        assertEquals(2.25, num("new Float16Array([1.5, 2.25])[1]"));
        assertEquals(2, num("Float16Array.BYTES_PER_ELEMENT"));
        assertEquals(1, num("new Float16Array(4).length - 3"));
    }

    @Test
    public void test_dataview_float16() {
        final var source = """
                const dv = new DataView(new ArrayBuffer(8));
                dv.setFloat16(0, 1.5, true);
                dv.getFloat16(0, true)
                """;
        assertEquals(1.5, num(source));
    }

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

    @Test
    public void test_array_buffer_transfer_detaches() {
        final var source = """
                const buf = new ArrayBuffer(4);
                const moved = buf.transfer();
                (buf.detached ? 10 : 0) + moved.byteLength
                """;
        assertEquals(14, num(source));
    }

    @Test
    public void test_array_buffer_transfer_explicit_length() {
        assertEquals(8, num("new ArrayBuffer(4).transfer(8).byteLength"));
        assertEquals(2, num("new ArrayBuffer(4).transferToFixedLength(2).byteLength"));
    }

    @Test
    public void test_array_buffer_transfer_keeps_resizable() {
        final var source = """
                const buf = new ArrayBuffer(4, { maxByteLength: 16 });
                const moved = buf.transfer();
                (moved.resizable ? 100 : 0) + moved.maxByteLength
                """;
        assertEquals(116, num(source));
    }

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

    @Test
    public void test_int32_write_out_of_range_wraps() {
        assertEquals(0, num("const a = new Int32Array(1); a[0] = 1e300; a[0]"));
        assertEquals(1410065408, num("const a = new Int32Array(1); a[0] = 1e10; a[0]"));
        assertEquals(-1, num("const a = new Int32Array(1); a[0] = -1; a[0]"));
        assertEquals(0, num("const a = new Uint8Array(1); a[0] = 1e21; a[0]"));
        assertEquals(255, num("const a = new Uint8Array(1); a[0] = -1; a[0]"));
        assertEquals(0, num("const a = new Int16Array(1); a[0] = 9.223372036854776e18; a[0]"));
    }

    @Test
    public void test_data_view_write_out_of_range_wraps() {
        final var view = "const v = new DataView(new ArrayBuffer(8));";
        assertEquals(0, num(view + " v.setInt32(0, 1e300); v.getInt32(0)"));
        assertEquals(1410065408, num(view + " v.setInt32(0, 1e10); v.getInt32(0)"));
        assertEquals(255, num(view + " v.setUint8(0, -1); v.getUint8(0)"));
    }

    @Test
    public void test_typed_array_includes_bigint() {
        assertTrue(bool("new BigInt64Array([1n]).includes(1n)"));
        assertFalse(bool("new BigInt64Array([1n]).includes(2n)"));
    }

    @Test
    public void test_typed_array_includes_from_index() {
        assertFalse(bool("new Int8Array([1, 2]).includes(1, 1)"));
        assertTrue(bool("new Int8Array([1, 2]).includes(2, -1)"));
    }

    @Test
    public void test_uint8_base64_round_trip() {
        assertEquals("AQID", str("new Uint8Array([1, 2, 3]).toBase64()"));
        assertEquals("1,2,3", str("Array.from(Uint8Array.fromBase64('AQID')).join(',')"));
    }

    @Test
    public void test_uint8_base64_options() {
        assertEquals("--8", str("new Uint8Array([251, 239]).toBase64({alphabet: 'base64url', omitPadding: true})"));
        assertEquals("--8=", str("new Uint8Array([251, 239]).toBase64({alphabet: 'base64url'})"));
        assertEquals("251,239", str("Array.from(Uint8Array.fromBase64('--8=', {alphabet: 'base64url'})).join(',')"));
    }

    @Test
    public void test_uint8_base64_unknown_alphabet_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Uint8Array(1).toBase64({alphabet: 'x'})"));
    }

    @Test
    public void test_uint8_hex_round_trip() {
        assertEquals("0aff", str("new Uint8Array([10, 255]).toHex()"));
        assertEquals("10,255", str("Array.from(Uint8Array.fromHex('0AFF')).join(',')"));
    }

    @Test
    public void test_uint8_malformed_input_throws() {
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('abc')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromHex('zz')"));
        assertThrows(SyntaxErrorException.class, () -> Interpreter.run("Uint8Array.fromBase64('!!!!')"));
    }

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

    @Test
    public void test_base64_is_uint8_only() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Uint16Array(1).toBase64()"));
        assertTrue(bool("typeof Uint8Array.fromBase64 === 'function'"));
        assertTrue(bool("Uint16Array.fromBase64 === undefined"));
    }
}
