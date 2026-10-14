package org.techhouse.ejson.internal;

final class JsonEscapes {
    private static final int UNICODE_ESCAPE_DIGITS = 4;
    private static final int HEX_RADIX = 16;

    private JsonEscapes() {
    }

    static String unescape(String raw, int firstEscape) {
        final var builder = new StringBuilder(raw.length());
        builder.append(raw, 0, firstEscape);
        var index = firstEscape;
        while (index < raw.length()) {
            final var c = raw.charAt(index);
            if (c == '\\' && index + 1 < raw.length()) {
                index = appendEscape(raw, builder, index + 1);
            } else {
                builder.append(c);
                index++;
            }
        }
        return builder.toString();
    }

    private static int appendEscape(String raw, StringBuilder builder, int escaped) {
        final var c = raw.charAt(escaped);
        switch (c) {
            case '"' -> builder.append('"');
            case '\\' -> builder.append('\\');
            case '/' -> builder.append('/');
            case 'b' -> builder.append('\b');
            case 'f' -> builder.append('\f');
            case 'n' -> builder.append('\n');
            case 'r' -> builder.append('\r');
            case 't' -> builder.append('\t');
            case 'u' -> {
                return appendUnicodeEscape(raw, builder, escaped + 1);
            }
            default -> builder.append('\\').append(c);
        }
        return escaped + 1;
    }

    private static int appendUnicodeEscape(String raw, StringBuilder builder, int digits) {
        if (digits + UNICODE_ESCAPE_DIGITS > raw.length()) {
            builder.append("\\u");
            return digits;
        }
        var codeUnit = 0;
        for (var offset = 0; offset < UNICODE_ESCAPE_DIGITS; offset++) {
            final var digit = Character.digit(raw.charAt(digits + offset), HEX_RADIX);
            if (digit < 0) {
                builder.append("\\u");
                return digits;
            }
            codeUnit = codeUnit * HEX_RADIX + digit;
        }
        builder.append((char) codeUnit);
        return digits + UNICODE_ESCAPE_DIGITS;
    }
}
