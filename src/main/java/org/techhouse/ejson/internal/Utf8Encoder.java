package org.techhouse.ejson.internal;

public final class Utf8Encoder {
    private static final byte REPLACEMENT = (byte) '?';
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;

    private Utf8Encoder() {
    }

    public static byte[] encode(CharSequence chars) {
        final var length = chars.length();
        final var bytes = new byte[encodedLength(chars, length)];
        fill(chars, length, bytes);
        return bytes;
    }

    private static int encodedLength(CharSequence chars, int length) {
        long total = 0;
        var i = 0;
        while (i < length) {
            final var c = chars.charAt(i);
            if (c < 0x80) {
                total++;
            } else if (c < 0x800) {
                total += 2;
            } else if (!Character.isSurrogate(c)) {
                total += 3;
            } else if (startsPair(chars, i, length)) {
                total += 4;
                i++;
            } else {
                total++;
            }
            i++;
        }
        if (total > MAX_ARRAY_LENGTH) {
            throw new OutOfMemoryError("The UTF-8 encoding of " + length + " chars exceeds the largest array");
        }
        return (int) total;
    }

    private static boolean startsPair(CharSequence chars, int index, int length) {
        return Character.isHighSurrogate(chars.charAt(index)) && index + 1 < length
                && Character.isLowSurrogate(chars.charAt(index + 1));
    }

    private static void fill(CharSequence chars, int length, byte[] bytes) {
        var out = 0;
        var i = 0;
        while (i < length) {
            final var c = chars.charAt(i);
            if (c < 0x80) {
                bytes[out++] = (byte) c;
            } else if (c < 0x800) {
                bytes[out++] = (byte) (0xC0 | c >> 6);
                bytes[out++] = (byte) (0x80 | c & 0x3F);
            } else if (!Character.isSurrogate(c)) {
                bytes[out++] = (byte) (0xE0 | c >> 12);
                bytes[out++] = (byte) (0x80 | c >> 6 & 0x3F);
                bytes[out++] = (byte) (0x80 | c & 0x3F);
            } else if (startsPair(chars, i, length)) {
                final var codePoint = Character.toCodePoint(c, chars.charAt(i + 1));
                bytes[out++] = (byte) (0xF0 | codePoint >> 18);
                bytes[out++] = (byte) (0x80 | codePoint >> 12 & 0x3F);
                bytes[out++] = (byte) (0x80 | codePoint >> 6 & 0x3F);
                bytes[out++] = (byte) (0x80 | codePoint & 0x3F);
                i++;
            } else {
                bytes[out++] = REPLACEMENT;
            }
            i++;
        }
    }
}
