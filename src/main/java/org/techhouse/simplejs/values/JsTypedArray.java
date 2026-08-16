package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.NumberBuiltins;
import org.techhouse.simplejs.internal.JsCoercion;

/**
 * A typed-array view over a {@link JsArrayBuffer}. Elements are read/written through the buffer's
 * shared bytes in little-endian order (the platform endianness JS exposes for typed arrays), coerced
 * to and from the view's {@link Kind}. BigInt kinds surface {@link JsBigInt} elements; all others
 * surface {@link JsNumber}.
 */
public final class JsTypedArray extends JsValue {
    private PropertyTable table;

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

    /** The observable {@code byteOffset}, which the spec reports as 0 once the view is out of bounds. */
    public int byteOffset() {
        return isOutOfBounds() ? 0 : byteOffset;
    }

    /** The view's own offset regardless of bounds, for deriving a new view's geometry from this one. */
    public int rawByteOffset() {
        return byteOffset;
    }

    public boolean isLengthTracking() {
        return lengthTracking;
    }

    /**
     * IsTypedArrayOutOfBounds: a view whose window no longer fits inside its buffer. A detached buffer
     * makes every view out of bounds; a shrunk resizable buffer makes only the views that no longer
     * fit out of bounds (a length-tracking view just gets shorter, unless its very offset is gone).
     */
    public boolean isOutOfBounds() {
        if (buffer.isDetached()) {
            return true;
        }
        final var bufferLength = buffer.byteLength();
        if (byteOffset > bufferLength) {
            return true;
        }
        if (lengthTracking) {
            return false;
        }
        return byteOffset + (long) length * kind.bytesPerElement > bufferLength;
    }

    public int byteLength() {
        return length() * kind.bytesPerElement;
    }

    public int length() {
        if (isOutOfBounds()) {
            return 0;
        }
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
        setElement(index, value, null);
    }

    // IntegerIndexedElementSet coerces the value *before* checking the index, so a valueOf that
    // detaches or shrinks the buffer still runs and only then is the write dropped. Without an ops
    // seam there is no user code to run, so an already-invalid index skips the coercion rather than
    // raising a TypeError the spec would never reach.
    public void setElement(int index, JsValue value, InterpreterOps ops) {
        if (ops == null && !isValidIndex(index)) {
            return;
        }
        if (kind == Kind.BIGINT64 || kind == Kind.BIGUINT64) {
            final var big = reduceBig(value, ops);
            if (isValidIndex(index)) {
                view().putLong(byteOffset + index * kind.bytesPerElement, big.longValue());
            }
            return;
        }
        final var number = JsCoercion.toNumber(value, ops);
        if (!isValidIndex(index)) {
            return;
        }
        final var pos = byteOffset + index * kind.bytesPerElement;
        final var bb = view();
        switch (kind) {
            case INT8, UINT8 -> bb.put(pos, (byte) reduce(number, 8));
            case UINT8CLAMPED -> bb.put(pos, (byte) clamp(number));
            case INT16, UINT16 -> bb.putShort(pos, (short) reduce(number, 16));
            case INT32, UINT32 -> bb.putInt(pos, (int) reduce(number, 32));
            case FLOAT16 -> bb.putShort(pos, Float.floatToFloat16((float) number));
            case FLOAT32 -> bb.putFloat(pos, (float) number);
            default -> bb.putDouble(pos, number);
        }
    }

    private boolean isValidIndex(int index) {
        final var pos = byteOffset + (long) index * kind.bytesPerElement;
        return index >= 0 && index < length() && pos + kind.bytesPerElement <= buffer.byteLength();
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

    private static BigInteger reduceBig(JsValue value, InterpreterOps ops) {
        return NumberBuiltins.toBigIntValue(value, ops).getValue().mod(BigInteger.ONE.shiftLeft(64));
    }

    private static BigInteger toUnsignedBig(long raw) {
        return raw >= 0 ? BigInteger.valueOf(raw) : BigInteger.valueOf(raw).add(BigInteger.ONE.shiftLeft(64));
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
