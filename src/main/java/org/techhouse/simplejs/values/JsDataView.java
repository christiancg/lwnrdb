package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * A JavaScript {@code DataView} over a {@link JsArrayBuffer}. Unlike a typed array, each getter/setter
 * takes an explicit {@code littleEndian} flag (default big-endian per spec).
 */
public final class JsDataView extends JsValue {
    private final JsArrayBuffer buffer;
    private final int byteOffset;
    private final int byteLength;
    private final boolean lengthTracking;

    public JsDataView(JsArrayBuffer buffer, int byteOffset, int byteLength) {
        this(buffer, byteOffset, byteLength, false);
    }

    public JsDataView(JsArrayBuffer buffer, int byteOffset, int byteLength, boolean lengthTracking) {
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.byteLength = byteLength;
        this.lengthTracking = lengthTracking;
    }

    public JsArrayBuffer getBuffer() {
        return buffer;
    }

    public int byteOffset() {
        return byteOffset;
    }

    public int byteLength() {
        if (lengthTracking) {
            final var available = buffer.byteLength() - byteOffset;
            return Math.max(available, 0);
        }
        return byteLength;
    }

    private void checkBounds(int offset, int size) {
        if (offset < 0 || offset + size > byteLength()) {
            throw new RangeErrorException("Offset is outside the bounds of the DataView");
        }
    }

    private ByteBuffer view(boolean littleEndian) {
        return ByteBuffer.wrap(buffer.getBytes()).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

    public double getNumber(String kind, int offset, boolean littleEndian) {
        checkBounds(offset, elementSize(kind));
        final var pos = byteOffset + offset;
        final var bb = view(littleEndian);
        return switch (kind) {
            case "getInt8" -> bb.get(pos);
            case "getUint8" -> bb.get(pos) & 0xFF;
            case "getInt16" -> bb.getShort(pos);
            case "getUint16" -> bb.getShort(pos) & 0xFFFF;
            case "getInt32" -> bb.getInt(pos);
            case "getUint32" -> bb.getInt(pos) & 0xFFFFFFFFL;
            case "getFloat16" -> Float.float16ToFloat(bb.getShort(pos));
            case "getFloat32" -> bb.getFloat(pos);
            default -> bb.getDouble(pos);
        };
    }

    public void setNumber(String kind, int offset, double value, boolean littleEndian) {
        checkBounds(offset, elementSize(kind));
        final var pos = byteOffset + offset;
        final var bb = view(littleEndian);
        switch (kind) {
            case "setInt8", "setUint8" -> bb.put(pos, (byte) (long) value);
            case "setInt16", "setUint16" -> bb.putShort(pos, (short) (long) value);
            case "setInt32", "setUint32" -> bb.putInt(pos, (int) (long) value);
            case "setFloat16" -> bb.putShort(pos, Float.floatToFloat16((float) value));
            case "setFloat32" -> bb.putFloat(pos, (float) value);
            default -> bb.putDouble(pos, value);
        }
    }

    public BigInteger getBigInt(boolean unsigned, int offset, boolean littleEndian) {
        checkBounds(offset, 8);
        final var raw = view(littleEndian).getLong(byteOffset + offset);
        if (!unsigned || raw >= 0) {
            return BigInteger.valueOf(raw);
        }
        return BigInteger.valueOf(raw).add(BigInteger.ONE.shiftLeft(64));
    }

    public void setBigInt(int offset, BigInteger value, boolean littleEndian) {
        checkBounds(offset, 8);
        view(littleEndian).putLong(byteOffset + offset, value.mod(BigInteger.ONE.shiftLeft(64)).longValue());
    }

    private static int elementSize(String kind) {
        if (kind.endsWith("8")) {
            return 1;
        }
        if (kind.endsWith("16")) {
            return 2;
        }
        if (kind.endsWith("32")) {
            return 4;
        }
        return 8;
    }
}
