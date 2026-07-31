package org.techhouse.simplejs.values;

import java.util.Arrays;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;

/**
 * A JavaScript {@code ArrayBuffer}: a raw byte store shared by typed-array views and
 * {@code DataView}s. All views hold a reference to the same buffer, so writes through one view are
 * visible through the others. A resizable buffer may {@code resize} up to {@code maxByteLength};
 * {@code transfer} detaches this buffer and hands its bytes to a new one.
 */
public final class JsArrayBuffer extends JsValue {
    private byte[] bytes;
    private final int maxByteLength;
    private final boolean resizable;
    private boolean detached;

    public JsArrayBuffer(int byteLength) {
        this(byteLength, byteLength, false);
    }

    public JsArrayBuffer(byte[] bytes) {
        this.bytes = bytes;
        this.maxByteLength = bytes.length;
        this.resizable = false;
    }

    public JsArrayBuffer(int byteLength, int maxByteLength, boolean resizable) {
        this.bytes = new byte[Math.max(byteLength, 0)];
        this.maxByteLength = Math.max(maxByteLength, 0);
        this.resizable = resizable;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int byteLength() {
        return bytes.length;
    }

    public int maxByteLength() {
        return maxByteLength;
    }

    public boolean isResizable() {
        return resizable;
    }

    public boolean isDetached() {
        return detached;
    }

    public JsArrayBuffer slice(int begin, int end) {
        final var from = clamp(begin);
        final var to = Math.max(clamp(end), from);
        return new JsArrayBuffer(Arrays.copyOfRange(bytes, from, to));
    }

    public void resize(int newByteLength) {
        if (!resizable) {
            throw new TypeErrorException("ArrayBuffer is not resizable");
        }
        if (detached) {
            throw new TypeErrorException("Cannot perform ArrayBuffer.prototype.resize on a detached ArrayBuffer");
        }
        if (newByteLength < 0 || newByteLength > maxByteLength) {
            throw new RangeErrorException("Invalid ArrayBuffer resize length");
        }
        bytes = Arrays.copyOf(bytes, newByteLength);
    }

    public JsArrayBuffer transfer(int newByteLength, boolean fixedLength) {
        if (detached) {
            throw new TypeErrorException("Cannot perform ArrayBuffer.prototype.transfer on a detached ArrayBuffer");
        }
        final var length = newByteLength < 0 ? bytes.length : newByteLength;
        final var moved = Arrays.copyOf(bytes, length);
        detached = true;
        bytes = new byte[0];
        if (fixedLength || !resizable) {
            return new JsArrayBuffer(moved);
        }
        final var result = new JsArrayBuffer(length, maxByteLength, true);
        System.arraycopy(moved, 0, result.bytes, 0, Math.min(moved.length, result.bytes.length));
        return result;
    }

    private int clamp(int index) {
        final var resolved = index < 0 ? bytes.length + index : index;
        return Math.clamp(resolved, 0, bytes.length);
    }
}
