package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.internal.JsCoercion;

/**
 * A typed-array view over a {@link JsArrayBuffer}. Elements are read/written through the buffer's
 * shared bytes in little-endian order (the platform endianness JS exposes for typed arrays), coerced
 * to and from the view's {@link Kind}. BigInt kinds surface {@link JsBigInt} elements; all others
 * surface {@link JsNumber}.
 */
public final class JsTypedArray extends JsValue {
    public enum Kind {
        INT8("Int8Array", 1), UINT8("Uint8Array", 1), UINT8CLAMPED("Uint8ClampedArray", 1), INT16("Int16Array",
                2), UINT16("Uint16Array", 2), INT32("Int32Array", 4), UINT32("Uint32Array", 4), FLOAT16("Float16Array",
                        2), FLOAT32("Float32Array", 4), FLOAT64("Float64Array",
                                8), BIGINT64("BigInt64Array", 8), BIGUINT64("BigUint64Array", 8);

        private final String ctorName;
        private final int bytesPerElement;

        Kind(String ctorName, int bytesPerElement) {
            this.ctorName = ctorName;
            this.bytesPerElement = bytesPerElement;
        }

        public String ctorName() {
            return ctorName;
        }

        public int bytesPerElement() {
            return bytesPerElement;
        }
    }

    private final Kind kind;
    private final JsArrayBuffer buffer;
    private final int byteOffset;
    private final int length;
    private final boolean lengthTracking;

    public JsTypedArray(Kind kind, JsArrayBuffer buffer, int byteOffset, int length) {
        this(kind, buffer, byteOffset, length, false);
    }

    public JsTypedArray(Kind kind, JsArrayBuffer buffer, int byteOffset, int length, boolean lengthTracking) {
        this.kind = kind;
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.length = length;
        this.lengthTracking = lengthTracking;
    }

    public Kind kind() {
        return kind;
    }

    public JsArrayBuffer getBuffer() {
        return buffer;
    }

    public int byteOffset() {
        return byteOffset;
    }

    public int byteLength() {
        return length() * kind.bytesPerElement;
    }

    public int length() {
        if (lengthTracking) {
            final var available = buffer.byteLength() - byteOffset;
            return available <= 0 ? 0 : available / kind.bytesPerElement;
        }
        return length;
    }

    private ByteBuffer view() {
        return ByteBuffer.wrap(buffer.getBytes()).order(ByteOrder.LITTLE_ENDIAN);
    }

    public JsValue getElement(int index) {
        final var pos = byteOffset + index * kind.bytesPerElement;
        if (index < 0 || index >= length() || pos + kind.bytesPerElement > buffer.byteLength()) {
            return JsUndefined.getInstance();
        }
        final var bb = view();
        return switch (kind) {
            case INT8 -> new JsNumber(bb.get(pos));
            case UINT8, UINT8CLAMPED -> new JsNumber(bb.get(pos) & 0xFF);
            case INT16 -> new JsNumber(bb.getShort(pos));
            case UINT16 -> new JsNumber(bb.getShort(pos) & 0xFFFF);
            case INT32 -> new JsNumber(bb.getInt(pos));
            case UINT32 -> new JsNumber(bb.getInt(pos) & 0xFFFFFFFFL);
            case FLOAT16 -> new JsNumber(Float.float16ToFloat(bb.getShort(pos)));
            case FLOAT32 -> new JsNumber(bb.getFloat(pos));
            case FLOAT64 -> new JsNumber(bb.getDouble(pos));
            case BIGINT64 -> new JsBigInt(BigInteger.valueOf(bb.getLong(pos)));
            case BIGUINT64 -> new JsBigInt(toUnsignedBig(bb.getLong(pos)));
        };
    }

    public void setElement(int index, JsValue value) {
        final var pos = byteOffset + index * kind.bytesPerElement;
        if (index < 0 || index >= length() || pos + kind.bytesPerElement > buffer.byteLength()) {
            return;
        }
        final var bb = view();
        switch (kind) {
            case INT8, UINT8 -> bb.put(pos, (byte) reduce(JsCoercion.toNumber(value), 8));
            case UINT8CLAMPED -> bb.put(pos, (byte) clamp(JsCoercion.toNumber(value)));
            case INT16, UINT16 -> bb.putShort(pos, (short) reduce(JsCoercion.toNumber(value), 16));
            case INT32, UINT32 -> bb.putInt(pos, (int) reduce(JsCoercion.toNumber(value), 32));
            case FLOAT16 -> bb.putShort(pos, Float.floatToFloat16((float) JsCoercion.toNumber(value)));
            case FLOAT32 -> bb.putFloat(pos, (float) JsCoercion.toNumber(value));
            case BIGINT64, BIGUINT64 -> bb.putLong(pos, reduceBig(value).longValue());
            default -> bb.putDouble(pos, JsCoercion.toNumber(value));
        }
    }

    private static long reduce(double d, int bits) {
        return NumberFormatter.toUint32(d) & ((1L << bits) - 1);
    }

    private static int clamp(double d) {
        if (Double.isNaN(d) || d <= 0) {
            return 0;
        }
        if (d >= 255) {
            return 255;
        }
        return (int) Math.rint(d);
    }

    private static BigInteger reduceBig(JsValue value) {
        return NumberBuiltins.toBigIntValue(value).getValue().mod(BigInteger.ONE.shiftLeft(64));
    }

    private static BigInteger toUnsignedBig(long raw) {
        return raw >= 0 ? BigInteger.valueOf(raw) : BigInteger.valueOf(raw).add(BigInteger.ONE.shiftLeft(64));
    }
}
