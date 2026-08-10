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
        while (start < length && !needsEscaping(value.charAt(start))) {
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
                default -> appendOther(builder, c);
            }
        }
        return builder.toString();
    }

    private static void appendOther(final StringBuilder builder, final char c) {
        if (c < LAST_CONTROL_CHARACTER) {
            builder.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
        } else {
            builder.append(c);
        }
    }

    private static boolean needsEscaping(final char c) {
        return c == '"' || c == '\\' || c < LAST_CONTROL_CHARACTER;
    }
}
