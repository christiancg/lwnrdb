package org.techhouse.ejson.internal;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

public final class Utf8JsonOut implements JsonOut {
    static final int MAX_POOLED_BYTES = 64 * 1024;
    private static final int MIN_BYTES = 256;
    private static final int HANDOVER_NUMERATOR = 3;
    private static final int HANDOVER_DENOMINATOR = 4;
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;
    private static final int MAX_BYTES_PER_CHAR = 3;
    private static final int SUPPLEMENTARY_BYTES = 4;
    private static final byte REPLACEMENT = (byte) '?';
    private static final ArrayBlockingQueue<byte[]> POOL = new ArrayBlockingQueue<>(
            2 * Runtime.getRuntime().availableProcessors());

    private byte[] buffer;
    private int size;

    private Utf8JsonOut(byte[] buffer) {
        this.buffer = buffer;
    }

    public static Utf8JsonOut acquire(int sizeHint) {
        final var pooled = POOL.poll();
        if (pooled != null && pooled.length >= sizeHint) {
            return new Utf8JsonOut(pooled);
        }
        return new Utf8JsonOut(new byte[Math.max(MIN_BYTES, sizeHint)]);
    }

    public boolean release() {
        final var released = buffer;
        buffer = null;
        return released != null && released.length <= MAX_POOLED_BYTES && POOL.offer(released);
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buffer, size);
    }

    public byte[] takeExact() {
        if (size != buffer.length) {
            return toByteArray();
        }
        final var taken = buffer;
        buffer = null;
        return taken;
    }

    public void digestInto(MessageDigest digest, int from, int to) {
        digest.update(buffer, from, to - from);
    }

    public EncodedJson handOver() {
        if ((long) size * HANDOVER_DENOMINATOR < (long) buffer.length * HANDOVER_NUMERATOR) {
            return EncodedJson.of(toByteArray());
        }
        final var handed = new EncodedJson(buffer, size);
        buffer = null;
        return handed;
    }

    public int size() {
        return size;
    }

    @Override
    public JsonOut append(char c) {
        if (c < 0x80) {
            ensure(1);
            buffer[size++] = (byte) c;
        } else if (Character.isSurrogate(c)) {
            ensure(1);
            buffer[size++] = REPLACEMENT;
        } else {
            ensure(bmpWidth(c));
            encodeBmp(c);
        }
        return this;
    }

    @Override
    public JsonOut append(String text, int start, int end) {
        ensure(end - start);
        var i = start;
        while (i < end) {
            final var c = text.charAt(i);
            if (c < 0x80) {
                if (size == buffer.length) {
                    ensure(end - i);
                }
                buffer[size++] = (byte) c;
                i++;
                continue;
            }
            if (!Character.isSurrogate(c)) {
                ensure(bmpWidth(c));
                encodeBmp(c);
            } else if (Character.isHighSurrogate(c) && i + 1 < end && Character.isLowSurrogate(text.charAt(i + 1))) {
                ensure(SUPPLEMENTARY_BYTES);
                encodeSupplementary(Character.toCodePoint(c, text.charAt(i + 1)));
                i++;
            } else {
                ensure(1);
                buffer[size++] = REPLACEMENT;
            }
            i++;
        }
        return this;
    }

    @Override
    public void appendEscaped(String text) {
        final var length = text.length();
        ensure(length);
        var i = 0;
        while (i < length) {
            final var c = text.charAt(i);
            if (c >= ' ' && c < 0x80 && c != '"' && c != '\\') {
                if (size == buffer.length) {
                    ensure(length - i);
                }
                buffer[size++] = (byte) c;
                i++;
            } else {
                i = appendEscapedSlow(text, i);
            }
        }
    }

    private int appendEscapedSlow(String text, int index) {
        final var c = text.charAt(index);
        if (JsonStrings.needsEscaping(text, index)) {
            JsonStrings.appendEscape(this, c);
            return index + 1;
        }
        if (Character.isHighSurrogate(c)) {
            ensure(SUPPLEMENTARY_BYTES);
            encodeSupplementary(Character.toCodePoint(c, text.charAt(index + 1)));
            return index + 2;
        }
        ensure(bmpWidth(c));
        encodeBmp(c);
        return index + 1;
    }

    @Override
    public void appendLong(long value) {
        var remaining = value;
        if (remaining < 0) {
            ensure(1);
            buffer[size++] = '-';
        } else {
            remaining = -remaining;
        }
        ensure(digitsOf(remaining));
        final var start = size;
        do {
            buffer[size++] = (byte) ('0' - remaining % 10);
            remaining /= 10;
        } while (remaining != 0);
        for (int left = start, right = size - 1; left < right; left++, right--) {
            final var digit = buffer[left];
            buffer[left] = buffer[right];
            buffer[right] = digit;
        }
    }

    @Override
    public int mark() {
        return size;
    }

    @Override
    public void reset(int mark) {
        size = mark;
    }

    private static int digitsOf(long negative) {
        var digits = 1;
        for (var rest = negative / 10; rest != 0; rest /= 10) {
            digits++;
        }
        return digits;
    }

    private static int bmpWidth(char c) {
        return c < 0x800 ? 2 : MAX_BYTES_PER_CHAR;
    }

    private void encodeBmp(char c) {
        if (c < 0x800) {
            buffer[size++] = (byte) (0xC0 | c >> 6);
        } else {
            buffer[size++] = (byte) (0xE0 | c >> 12);
            buffer[size++] = (byte) (0x80 | c >> 6 & 0x3F);
        }
        buffer[size++] = (byte) (0x80 | c & 0x3F);
    }

    private void encodeSupplementary(int codePoint) {
        buffer[size++] = (byte) (0xF0 | codePoint >> 18);
        buffer[size++] = (byte) (0x80 | codePoint >> 12 & 0x3F);
        buffer[size++] = (byte) (0x80 | codePoint >> 6 & 0x3F);
        buffer[size++] = (byte) (0x80 | codePoint & 0x3F);
    }

    private void ensure(long extra) {
        if (size + extra <= buffer.length) {
            return;
        }
        final var needed = size + extra;
        if (needed > MAX_ARRAY_LENGTH) {
            throw new OutOfMemoryError("The UTF-8 encoding needs more than the largest array");
        }
        buffer = Arrays.copyOf(buffer, Math.clamp(Math.max(needed, buffer.length * 2L), MIN_BYTES, MAX_ARRAY_LENGTH));
    }
}
