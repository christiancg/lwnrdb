package org.techhouse.config;

import java.util.function.Function;

public record Rule(Function<String, String> check) {
    private static final Rule NONE = new Rule(_ -> null);

    public static Rule none() {
        return NONE;
    }

    public static Rule bool() {
        return new Rule(value -> "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)
                ? null
                : "must be true or false, but was: " + value);
    }

    public static Rule atLeast(long min) {
        return new Rule(value -> {
            final var parsed = parseLong(value);
            if (parsed == null) {
                return "must be a valid number, but was: " + value;
            }
            return parsed >= min ? null : "must be greater than or equal to " + min + ", but was: " + value;
        });
    }

    public static Rule range(long min, long max) {
        return new Rule(value -> {
            final var parsed = parseLong(value);
            if (parsed == null) {
                return "must be a valid number, but was: " + value;
            }
            return parsed >= min && parsed <= max
                    ? null
                    : "must be between " + min + " and " + max + ", but was: " + value;
        });
    }

    public static Rule positiveSize() {
        return new Rule(value -> {
            try {
                return SizeParser.parse(value) > 0 ? null : "must be greater than 0, but was: " + value;
            } catch (RuntimeException ignored) {
                return "must be a valid size, but was: " + value;
            }
        });
    }

    // -1 disables the cache and 0 means unlimited, so the ordinary positive-size rule cannot apply.
    public static Rule memory() {
        return new Rule(value -> {
            final var trimmed = value == null ? "" : value.trim();
            if (Globals.CACHE_DISABLED == parseOrNull(trimmed) || Globals.CACHE_UNLIMITED == parseOrNull(trimmed)) {
                return null;
            }
            return positiveSize().check().apply(value);
        });
    }

    private static long parseOrNull(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static Long parseLong(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
