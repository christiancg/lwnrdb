package org.techhouse.simplejs.values;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;

/**
 * A JavaScript {@code DataView} over a {@link JsArrayBuffer}. Unlike a typed array, each getter/setter
 * takes an explicit {@code littleEndian} flag (default big-endian per spec).
 */
public final class JsDataView extends JsValue {
    private PropertyTable table;

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

    /**
     * IsViewOutOfBounds: a detached buffer, or a resizable buffer shrunk past this view's window.
     */
    public boolean isOutOfBounds() {
        if (buffer.isDetached()) {
            return true;
        }
        final var bufferLength = buffer.byteLength();
        if (byteOffset > bufferLength) {
            return true;
        }
        return !lengthTracking && byteOffset + (long) byteLength > bufferLength;
    }

    /**
     * The spec's {@code get byteLength} throws on an out-of-bounds view rather than reporting a stale
     * length, so every geometry read has to go through the same check the element accessors do.
     */
    public int byteLength() {
        if (isOutOfBounds()) {
            throw new TypeErrorException("DataView is out of bounds");
        }
        if (lengthTracking) {
            final var available = buffer.byteLength() - byteOffset;
            return Math.max(available, 0);
        }
        return byteLength;
    }

    // offset arrives as a long (post-ToIndex, so it may exceed Integer.MAX_VALUE) - the comparison
    // must not overflow the way `int + int` would for a huge, spec-legal-but-out-of-buffer offset.
    private int checkBounds(long offset, int size) {
        // A detached buffer's backing bytes are truncated to zero-length, so this must be checked
        // before the bounds comparison below - otherwise a non-lengthTracking view still reports
        // its original (now-stale) byteLength and the out-of-range ByteBuffer access throws a raw
        // IndexOutOfBoundsException instead of the spec TypeError.
        if (buffer.isDetached()) {
            throw new TypeErrorException("Cannot perform DataView access on a detached ArrayBuffer");
        }
        if (isOutOfBounds()) {
            throw new TypeErrorException("DataView is out of bounds");
        }
        if (offset < 0 || offset + size > byteLength()) {
            throw new RangeErrorException("Offset is outside the bounds of the DataView");
        }
        return (int) offset;
    }

    private ByteBuffer view(boolean littleEndian) {
        return ByteBuffer.wrap(buffer.getBytes()).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
    }

    public double getNumber(String kind, long offset, boolean littleEndian) {
        final var pos = byteOffset + checkBounds(offset, elementSize(kind));
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

    public void setNumber(String kind, long offset, double value, boolean littleEndian) {
        final var pos = byteOffset + checkBounds(offset, elementSize(kind));
        final var bb = view(littleEndian);
        switch (kind) {
            case "setInt8", "setUint8" -> bb.put(pos, (byte) NumberFormatter.toInt32(value));
            case "setInt16", "setUint16" -> bb.putShort(pos, (short) NumberFormatter.toInt32(value));
            case "setInt32", "setUint32" -> bb.putInt(pos, NumberFormatter.toInt32(value));
            case "setFloat16" -> bb.putShort(pos, Float.floatToFloat16((float) value));
            case "setFloat32" -> bb.putFloat(pos, (float) value);
            default -> bb.putDouble(pos, value);
        }
    }

    public BigInteger getBigInt(boolean unsigned, long offset, boolean littleEndian) {
        final var pos = byteOffset + checkBounds(offset, 8);
        final var raw = view(littleEndian).getLong(pos);
        if (!unsigned || raw >= 0) {
            return BigInteger.valueOf(raw);
        }
        return BigInteger.valueOf(raw).add(BigInteger.ONE.shiftLeft(64));
    }

    public void setBigInt(long offset, BigInteger value, boolean littleEndian) {
        final var pos = byteOffset + checkBounds(offset, 8);
        view(littleEndian).putLong(pos, value.mod(BigInteger.ONE.shiftLeft(64)).longValue());
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

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
