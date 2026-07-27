package org.techhouse.simplejs.values;

import java.util.Arrays;

/**
 * A JavaScript {@code ArrayBuffer}: a fixed-length raw byte store shared by typed-array views and
 * {@code DataView}s. All views hold a reference to the same {@code byte[]}, so writes through one
 * view are visible through the others.
 */
public final class JsArrayBuffer extends JsValue {
    private final byte[] bytes;

    public JsArrayBuffer(int byteLength) {
        this.bytes = new byte[Math.max(byteLength, 0)];
    }

    public JsArrayBuffer(byte[] bytes) {
        this.bytes = bytes;
    }

    public byte[] getBytes() {
        return bytes;
    }

    public int byteLength() {
        return bytes.length;
    }

    public JsArrayBuffer slice(int begin, int end) {
        final var from = clamp(begin);
        final var to = Math.max(clamp(end), from);
        return new JsArrayBuffer(Arrays.copyOfRange(bytes, from, to));
    }

    private int clamp(int index) {
        final var resolved = index < 0 ? bytes.length + index : index;
        return Math.clamp(resolved, 0, bytes.length);
    }
}
