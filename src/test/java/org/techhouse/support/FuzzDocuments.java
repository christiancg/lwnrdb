package org.techhouse.support;

import java.util.List;
import java.util.Random;

public final class FuzzDocuments {
    private static final int MAX_DEPTH = 2;
    private static final List<String> NUMBERS = List.of("0", "-0.0", "1", "-1", "12", "12.0", "9007199254740991",
            "9007199254740992", "9007199254740993", "2147483647", "-2147483648", "1e21", "1e-7", "3e9", "5e-324",
            "1.7976931348623157e308", "1E5", "1.5e+3", "-4.9E-324", "0.1", "-12.75");
    private static final List<String> STRINGS = List.of("", "a", "A", "abc", "ABC", "ab", "a?b", "\n", "\r\n", "\\",
            "\\u001f", "\u0000", "\u007f", "\ud800", "\udc00", "a\ud800b", "😀‍💻", "#geo(1,2)",
            "#datetime(2024-01-01T10:00)", "Infinity", "NaN", "null", "true", "a".repeat(64), "a".repeat(65), "café",
            "日本", "quote\"inside", "tab\there", " ", "#notacustom", "\\q", "/slash/");
    private static final List<String> CUSTOM = List.of("#geo(0,0)", "#geo(90,0)", "#geo(-90,0)", "#geo(0,180)",
            "#geo(0.1,179.95)", "#geo(51.5,-0.12)", "#vector(1,0,0)", "#vector(1.0,0.0,0.0)", "#vector(-1,-1,-1)",
            "#vector(0.5,0.5,0.7071)", "#datetime(2024-01-01T10:00)", "#datetime(2024-01-01T10:00:00)",
            "#datetime(2024-01-01T10:00:00.000)", "#datetime(1970-01-01T00:00)", "#time(10:00)", "#time(10:00:00)",
            "#time(23:59:59)");
    private static final List<String> RAW = List.of("1e400", "-1e400", "1" + "0".repeat(400), "{\"dup\":1,\"dup\":2}",
            "{\"\":1}");
    private static final String HEX = "0123456789abcdef";

    private final Random rng;

    public FuzzDocuments(long seed) {
        this.rng = new Random(seed);
    }

    public String document(int index) {
        final var out = new StringBuilder("{\"_id\":\"doc-").append(index).append('"');
        final var fields = 1 + rng.nextInt(13);
        for (var field = 0; field < fields; field++) {
            out.append(',');
            string(fieldName(field), out);
            out.append(':');
            value(0, out);
        }
        return out.append('}').toString();
    }

    private String fieldName(int field) {
        return switch (rng.nextInt(10)) {
            case 0 -> "user-" + Long.toHexString(rng.nextLong());
            case 1 -> STRINGS.get(rng.nextInt(STRINGS.size()));
            default -> "field" + field;
        };
    }

    private void value(int depth, StringBuilder out) {
        if (depth >= MAX_DEPTH || rng.nextDouble() < 0.7) {
            scalar(out);
        } else if (rng.nextBoolean()) {
            out.append('[');
            final var size = rng.nextInt(4);
            for (var i = 0; i < size; i++) {
                if (i > 0) {
                    out.append(',');
                }
                value(depth + 1, out);
            }
            out.append(']');
        } else {
            out.append('{');
            final var size = rng.nextInt(4);
            for (var i = 0; i < size; i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append("\"k").append(i).append("\":");
                value(depth + 1, out);
            }
            out.append('}');
        }
    }

    private void scalar(StringBuilder out) {
        if (rng.nextDouble() < 0.02) {
            out.append(RAW.get(rng.nextInt(RAW.size())));
            return;
        }
        switch (rng.nextInt(6)) {
            case 0 -> out.append(NUMBERS.get(rng.nextInt(NUMBERS.size())));
            case 1, 2 -> string(STRINGS.get(rng.nextInt(STRINGS.size())), out);
            case 3 -> string(CUSTOM.get(rng.nextInt(CUSTOM.size())), out);
            case 4 -> out.append(rng.nextBoolean());
            default -> out.append("null");
        }
    }

    private void string(String value, StringBuilder out) {
        final var asciiOnly = rng.nextBoolean();
        out.append('"');
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> appendChar(c, asciiOnly, out);
            }
        }
        out.append('"');
    }

    private static void appendChar(char c, boolean asciiOnly, StringBuilder out) {
        if (c < 0x20 || (asciiOnly && c > 0x7e)) {
            out.append("\\u").append(HEX.charAt((c >> 12) & 0xF)).append(HEX.charAt((c >> 8) & 0xF))
                    .append(HEX.charAt((c >> 4) & 0xF)).append(HEX.charAt(c & 0xF));
        } else {
            out.append(c);
        }
    }
}
