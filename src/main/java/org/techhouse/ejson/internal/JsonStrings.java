package org.techhouse.ejson.internal;

public final class JsonStrings {
    private JsonStrings() {
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final char LAST_CONTROL_CHARACTER = 0x20;

    public static String escape(final String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        final var start = firstToEscape(value);
        if (start == value.length()) {
            return value;
        }
        final var builder = new StringBuilder(value.length() + 8);
        builder.append(value, 0, start);
        escapeFrom(builder, value, start);
        return builder.toString();
    }

    public static void appendEscaped(final StringBuilder out, final String value) {
        if (value == null) {
            out.append((String) null);
            return;
        }
        final var start = firstToEscape(value);
        if (start == value.length()) {
            out.append(value);
            return;
        }
        out.append(value, 0, start);
        escapeFrom(out, value, start);
    }

    private static int firstToEscape(final String value) {
        final var length = value.length();
        var index = 0;
        while (index < length && !needsEscaping(value, index)) {
            index++;
        }
        return index;
    }

    private static void escapeFrom(final StringBuilder builder, final String value, final int start) {
        final var length = value.length();
        for (var i = start; i < length; i++) {
            final var c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> appendOther(builder, value, i);
            }
        }
    }

    private static void appendOther(final StringBuilder builder, final String value, final int index) {
        final var c = value.charAt(index);
        if (c < LAST_CONTROL_CHARACTER || isLoneSurrogate(value, index)) {
            appendUnicodeEscape(builder, c);
        } else {
            builder.append(c);
        }
    }

    public static void appendUnicodeEscape(final StringBuilder builder, final char c) {
        builder.append("\\u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF]).append(HEX[(c >> 4) & 0xF])
                .append(HEX[c & 0xF]);
    }

    // An unpaired surrogate has no UTF-8 encoding, so emitting it raw corrupts the stored line; the
    // escaped form round-trips through the lexer unchanged.
    public static boolean isLoneSurrogate(final String value, final int index) {
        final var c = value.charAt(index);
        if (Character.isHighSurrogate(c)) {
            return index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1));
        }
        return Character.isLowSurrogate(c) && (index == 0 || !Character.isHighSurrogate(value.charAt(index - 1)));
    }

    private static boolean needsEscaping(final String value, final int index) {
        final var c = value.charAt(index);
        return c == '"' || c == '\\' || c < LAST_CONTROL_CHARACTER || isLoneSurrogate(value, index);
    }
}
