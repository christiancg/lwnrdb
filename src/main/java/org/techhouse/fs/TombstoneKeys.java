package org.techhouse.fs;

final class TombstoneKeys {
    private static final int ASCII_LIMIT = 0x80;
    private static final int WIDE_BYTES = 3;
    private static final int HIGH_SHIFT = 14;
    private static final int MID_SHIFT = 7;
    private static final int LOW_BITS = 0x7F;

    private TombstoneKeys() {
    }

    static int encodedLength(String id) {
        var length = 0;
        for (var i = 0; i < id.length(); i++) {
            length += id.charAt(i) < ASCII_LIMIT ? 1 : WIDE_BYTES;
        }
        return length;
    }

    static int encode(String id, byte[] target, int at) {
        var position = at;
        for (var i = 0; i < id.length(); i++) {
            final var current = id.charAt(i);
            if (current < ASCII_LIMIT) {
                target[position++] = (byte) current;
            } else {
                target[position++] = (byte) (ASCII_LIMIT | current >>> HIGH_SHIFT);
                target[position++] = (byte) (ASCII_LIMIT | current >>> MID_SHIFT & LOW_BITS);
                target[position++] = (byte) (ASCII_LIMIT | current & LOW_BITS);
            }
        }
        return position;
    }

    static byte[] encode(String id) {
        final var encoded = new byte[encodedLength(id)];
        encode(id, encoded, 0);
        return encoded;
    }

    static String decode(byte[] source, int from, int to) {
        if (isAscii(source, from, to)) {
            return new String(source, from, to - from, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        final var chars = new char[to - from];
        var length = 0;
        var position = from;
        while (position < to) {
            final var lead = source[position] & 0xFF;
            if (lead < ASCII_LIMIT) {
                chars[length++] = (char) lead;
                position++;
            } else {
                chars[length++] = (char) ((lead & LOW_BITS) << HIGH_SHIFT
                        | (source[position + 1] & LOW_BITS) << MID_SHIFT | source[position + 2] & LOW_BITS);
                position += WIDE_BYTES;
            }
        }
        return new String(chars, 0, length);
    }

    static boolean isAscii(byte[] source, int from, int to) {
        for (var position = from; position < to; position++) {
            if (source[position] < 0) {
                return false;
            }
        }
        return true;
    }

    static int compare(String id, byte[] keys, int from, int to) {
        var position = from;
        for (var i = 0; i < id.length(); i++) {
            final var current = id.charAt(i);
            if (current < ASCII_LIMIT) {
                if (position >= to) {
                    return 1;
                }
                final var difference = current - (keys[position++] & 0xFF);
                if (difference != 0) {
                    return difference;
                }
                continue;
            }
            final var difference = compareWide(current, keys, position, to);
            if (difference != 0) {
                return difference;
            }
            position += WIDE_BYTES;
        }
        return position < to ? -1 : 0;
    }

    private static int compareWide(char current, byte[] keys, int position, int to) {
        final int[] wide = {ASCII_LIMIT | current >>> HIGH_SHIFT, ASCII_LIMIT | current >>> MID_SHIFT & LOW_BITS,
                ASCII_LIMIT | current & LOW_BITS};
        for (var offset = 0; offset < WIDE_BYTES; offset++) {
            if (position + offset >= to) {
                return 1;
            }
            final var difference = wide[offset] - (keys[position + offset] & 0xFF);
            if (difference != 0) {
                return difference;
            }
        }
        return 0;
    }

    static int compare(byte[] left, int leftFrom, int leftTo, byte[] right, int rightFrom, int rightTo) {
        return java.util.Arrays.compareUnsigned(left, leftFrom, leftTo, right, rightFrom, rightTo);
    }
}
