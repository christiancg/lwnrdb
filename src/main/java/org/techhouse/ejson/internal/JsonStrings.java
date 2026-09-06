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
        final var length = value.length();
        var start = 0;
        while (start < length && !needsEscaping(value, start)) {
            start++;
        }
        if (start == length) {
            return value;
        }
        final var builder = new StringBuilder(length + 8);
        builder.append(value, 0, start);
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
        return builder.toString();
    }

    private static void appendOther(final StringBuilder builder, final String value, final int index) {
        final var c = value.charAt(index);
        if (c < LAST_CONTROL_CHARACTER || isLoneSurrogate(value, index)) {
            builder.append("\\u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF]).append(HEX[(c >> 4) & 0xF])
                    .append(HEX[c & 0xF]);
        } else {
            builder.append(c);
        }
    }

    // An unpaired surrogate cannot be encoded as UTF-8, so emitting it raw would corrupt the output;
    // the escaped form round-trips through the lexer unchanged.
    private static boolean isLoneSurrogate(final String value, final int index) {
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
