package org.techhouse.log;

public final class LogSanitizer {
    private static final char FIRST_PRINTABLE = 0x20;
    private static final char DELETE = 0x7f;
    private static final char NEXT_LINE = 0x85;
    private static final char LINE_SEPARATOR = 0x2028;
    private static final char PARAGRAPH_SEPARATOR = 0x2029;
    private static final String HEX_DIGITS = "0123456789abcdef";

    private LogSanitizer() {
    }

    public static String neutralise(String text) {
        if (text == null) {
            return null;
        }
        final var length = text.length();
        var first = 0;
        while (first < length && !needsEscape(text.charAt(first))) {
            first++;
        }
        if (first == length) {
            return text;
        }
        final var out = new StringBuilder(length + 16);
        out.append(text, 0, first);
        for (var i = first; i < length; i++) {
            final var c = text.charAt(i);
            if (needsEscape(c)) {
                appendEscape(out, c);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean needsEscape(char c) {
        return c < FIRST_PRINTABLE || c == DELETE || c == NEXT_LINE || c == LINE_SEPARATOR || c == PARAGRAPH_SEPARATOR;
    }

    private static void appendEscape(StringBuilder out, char c) {
        switch (c) {
            case '\r' -> out.append("\\r");
            case '\n' -> out.append("\\n");
            case '\t' -> out.append("\\t");
            default ->
                out.append("\\u").append(HEX_DIGITS.charAt(c >> 12 & 0xf)).append(HEX_DIGITS.charAt(c >> 8 & 0xf))
                        .append(HEX_DIGITS.charAt(c >> 4 & 0xf)).append(HEX_DIGITS.charAt(c & 0xf));
        }
    }
}
