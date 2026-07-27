package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsDataView;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public class JsTypedArrayTest {
    private static JsTypedArray allocate(JsTypedArray.Kind kind, int length) {
        return new JsTypedArray(kind, new JsArrayBuffer(length * kind.bytesPerElement()), 0, length);
    }

    private static double num(JsValue value) {
        return ((JsNumber) value).getValue();
    }

    // Each concrete typed-array/buffer/view value reports its own JsValueType
    @Test
    public void test_get_type() {
        assertEquals(JsValue.JsValueType.ARRAY_BUFFER, new JsArrayBuffer(4).getType());
        assertEquals(JsValue.JsValueType.TYPED_ARRAY, allocate(JsTypedArray.Kind.INT8, 1).getType());
        assertEquals(JsValue.JsValueType.DATA_VIEW, new JsDataView(new JsArrayBuffer(4), 0, 4).getType());
    }

    // Uint8 writes wrap modulo 256
    @Test
    public void test_uint8_wraparound() {
        final var array = allocate(JsTypedArray.Kind.UINT8, 2);
        array.setElement(0, new JsNumber(256));
        array.setElement(1, new JsNumber(257));
        assertEquals(0, num(array.getElement(0)));
        assertEquals(1, num(array.getElement(1)));
    }

    // Int8 writes wrap into the signed range
    @Test
    public void test_int8_signed_wraparound() {
        final var array = allocate(JsTypedArray.Kind.INT8, 2);
        array.setElement(0, new JsNumber(200));
        array.setElement(1, new JsNumber(-1));
        assertEquals(-56, num(array.getElement(0)));
        assertEquals(-1, num(array.getElement(1)));
    }

    // Uint8Clamped clamps out-of-range values instead of wrapping
    @Test
    public void test_uint8_clamped() {
        final var array = allocate(JsTypedArray.Kind.UINT8CLAMPED, 3);
        array.setElement(0, new JsNumber(300));
        array.setElement(1, new JsNumber(-5));
        array.setElement(2, new JsNumber(2.5));
        assertEquals(255, num(array.getElement(0)));
        assertEquals(0, num(array.getElement(1)));
        assertEquals(2, num(array.getElement(2)));
    }

    // Float32 loses precision relative to Float64
    @Test
    public void test_float_kinds() {
        final var f32 = allocate(JsTypedArray.Kind.FLOAT32, 1);
        f32.setElement(0, new JsNumber(0.1));
        final var f64 = allocate(JsTypedArray.Kind.FLOAT64, 1);
        f64.setElement(0, new JsNumber(0.1));
        assertEquals(0.1, num(f64.getElement(0)));
        assertEquals(0.1f, num(f32.getElement(0)));
    }

    // Uint32 surfaces the full unsigned range
    @Test
    public void test_uint32_range() {
        final var array = allocate(JsTypedArray.Kind.UINT32, 1);
        array.setElement(0, new JsNumber(-1));
        assertEquals(4294967295.0, num(array.getElement(0)));
    }

    // Int16/Uint16 read paths round-trip signed and unsigned values
    @Test
    public void test_int16_uint16() {
        final var signed = allocate(JsTypedArray.Kind.INT16, 1);
        signed.setElement(0, new JsNumber(-2));
        assertEquals(-2, num(signed.getElement(0)));
        final var unsigned = allocate(JsTypedArray.Kind.UINT16, 1);
        unsigned.setElement(0, new JsNumber(-1));
        assertEquals(65535, num(unsigned.getElement(0)));
    }

    // A positive BigUint64 value reads back through the non-negative branch
    @Test
    public void test_biguint64_positive() {
        final var array = allocate(JsTypedArray.Kind.BIGUINT64, 1);
        array.setElement(0, new JsBigInt(BigInteger.valueOf(42)));
        assertEquals(BigInteger.valueOf(42), ((JsBigInt) array.getElement(0)).getValue());
    }

    // BigInt64/BigUint64 round-trip through JsBigInt with signed/unsigned interpretation
    @Test
    public void test_bigint_kinds() {
        final var signed = allocate(JsTypedArray.Kind.BIGINT64, 1);
        signed.setElement(0, new JsBigInt(BigInteger.valueOf(-1)));
        assertEquals(BigInteger.valueOf(-1), ((JsBigInt) signed.getElement(0)).getValue());
        final var unsigned = allocate(JsTypedArray.Kind.BIGUINT64, 1);
        unsigned.setElement(0, new JsBigInt(BigInteger.valueOf(-1)));
        assertEquals(new BigInteger("18446744073709551615"), ((JsBigInt) unsigned.getElement(0)).getValue());
    }

    // Assigning a non-BigInt to a BigInt kind throws a TypeError
    @Test
    public void test_bigint_kind_rejects_number() {
        final var array = allocate(JsTypedArray.Kind.BIGINT64, 1);
        assertThrows(TypeErrorException.class, () -> array.setElement(0, new JsNumber(1)));
    }

    // Out-of-range indices read undefined and ignore writes
    @Test
    public void test_index_bounds() {
        final var array = allocate(JsTypedArray.Kind.INT8, 1);
        assertInstanceOf(JsUndefined.class, array.getElement(5));
        assertInstanceOf(JsUndefined.class, array.getElement(-1));
        array.setElement(5, new JsNumber(9));
        assertEquals(0, num(array.getElement(0)));
    }

    // A view's byteOffset/byteLength reflect its element kind and length
    @Test
    public void test_view_geometry() {
        final var buffer = new JsArrayBuffer(16);
        final var array = new JsTypedArray(JsTypedArray.Kind.INT32, buffer, 4, 2);
        assertEquals(4, array.byteOffset());
        assertEquals(8, array.byteLength());
        assertEquals(2, array.length());
        assertEquals(4, JsTypedArray.Kind.INT32.bytesPerElement());
    }

    // Two views over the same buffer see each other's writes
    @Test
    public void test_shared_buffer() {
        final var buffer = new JsArrayBuffer(4);
        final var a = new JsTypedArray(JsTypedArray.Kind.UINT8, buffer, 0, 4);
        final var b = new JsTypedArray(JsTypedArray.Kind.UINT8, buffer, 0, 4);
        a.setElement(2, new JsNumber(42));
        assertEquals(42, num(b.getElement(2)));
    }

    // ArrayBuffer.slice copies a byte range and supports negative indices
    @Test
    public void test_array_buffer_slice() {
        final var buffer = new JsArrayBuffer(8);
        final var view = new JsTypedArray(JsTypedArray.Kind.UINT8, buffer, 0, 8);
        for (var i = 0; i < 8; i++) {
            view.setElement(i, new JsNumber(i));
        }
        final var sliced = buffer.slice(-4, -1);
        assertEquals(3, sliced.byteLength());
        final var slicedView = new JsTypedArray(JsTypedArray.Kind.UINT8, sliced, 0, 3);
        assertEquals(4, num(slicedView.getElement(0)));
        assertEquals(6, num(slicedView.getElement(2)));
    }

    // DataView round-trips numbers with explicit endianness
    @Test
    public void test_data_view_endianness() {
        final var view = new JsDataView(new JsArrayBuffer(8), 0, 8);
        view.setNumber("setInt32", 0, -1, true);
        assertEquals(-1, view.getNumber("getInt32", 0, true));
        view.setNumber("setUint16", 0, 0x0102, false);
        assertEquals(0x0102, view.getNumber("getUint16", 0, false));
        // big-endian bytes are byte-swapped when read little-endian
        assertEquals(0x0201, view.getNumber("getUint16", 0, true));
    }

    // DataView round-trips a Float64 exactly
    @Test
    public void test_data_view_float64() {
        final var view = new JsDataView(new JsArrayBuffer(8), 0, 8);
        view.setNumber("setFloat64", 0, 3.141592653589793, true);
        assertEquals(3.141592653589793, view.getNumber("getFloat64", 0, true));
    }

    // DataView round-trips BigInt with signed/unsigned interpretation
    @Test
    public void test_data_view_bigint() {
        final var view = new JsDataView(new JsArrayBuffer(8), 0, 8);
        view.setBigInt(0, BigInteger.valueOf(-1), true);
        assertEquals(BigInteger.valueOf(-1), view.getBigInt(false, 0, true));
        assertEquals(new BigInteger("18446744073709551615"), view.getBigInt(true, 0, true));
    }

    // A typed array stringifies as a comma-joined list of its elements
    @Test
    public void test_typed_array_to_string() {
        final var array = allocate(JsTypedArray.Kind.INT8, 3);
        array.setElement(0, new JsNumber(1));
        array.setElement(1, new JsNumber(2));
        array.setElement(2, new JsNumber(3));
        assertEquals("1,2,3", JsCoercion.toStr(array));
        assertEquals("object", JsCoercion.typeOf(array));
        assertEquals("[object ArrayBuffer]", JsCoercion.toStr(new JsArrayBuffer(4)));
        assertEquals("[object DataView]", JsCoercion.toStr(new JsDataView(new JsArrayBuffer(4), 0, 4)));
    }
}
