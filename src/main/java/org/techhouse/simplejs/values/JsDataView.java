package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A JavaScript {@code DataView} over a {@link JsArrayBuffer}. Unlike a typed array, each getter/setter
 * takes an explicit {@code littleEndian} flag (default big-endian per spec).
 */
public final class JsDataView extends JsValue {
    private final JsArrayBuffer buffer;
    private final int byteOffset;
    private final int byteLength;

    public JsDataView(JsArrayBuffer buffer, int byteOffset, int byteLength) {
        this.buffer = buffer;
        this.byteOffset = byteOffset;
        this.byteLength = byteLength;
    }

    public JsArrayBuffer getBuffer() {
        return buffer;
    }

    public int byteOffset() {
        return byteOffset;
    }

    public int byteLength() {
        return byteLength;
    }

    private ByteBuffer view(boolean littleEndian) {
        return ByteBuffer.wrap(buffer.getBytes()).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

    public double getNumber(String kind, int offset, boolean littleEndian) {
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
        final var raw = view(littleEndian).getLong(byteOffset + offset);
        if (!unsigned || raw >= 0) {
            return BigInteger.valueOf(raw);
        }
        return BigInteger.valueOf(raw).add(BigInteger.ONE.shiftLeft(64));
    }

    public void setBigInt(int offset, BigInteger value, boolean littleEndian) {
        view(littleEndian).putLong(byteOffset + offset, value.mod(BigInteger.ONE.shiftLeft(64)).longValue());
    }
}
