package org.techhouse.config;

import java.net.InetAddress;
import java.util.function.Function;
import java.util.regex.Pattern;

public record Rule(Function<String, String> check) {
    private static final Rule NONE = new Rule(_ -> null);
    private static final Pattern LITERAL_LIKE = Pattern.compile("[0-9.]+|.*:.*");
    private static final Pattern HOST_NAME = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    public static Rule none() {
        return NONE;
    }

    public static Rule oneOf(String... allowed) {
        final var options = java.util.List.of(allowed);
        return new Rule(value -> options.contains(value.toUpperCase(java.util.Locale.ROOT))
                ? null
                : "must be one of " + options + ", but was: " + value);
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

    public static Rule nonNegativeSize() {
        return new Rule(value -> {
            try {
                return SizeParser.parse(value) >= 0 ? null : "must be 0 or a positive size, but was: " + value;
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

    public static Rule bindAddress() {
        return new Rule(value -> {
            final var trimmed = value == null ? "" : value.trim();
            final var valid = LITERAL_LIKE.matcher(trimmed).matches()
                    ? isAddressLiteral(trimmed)
                    : HOST_NAME.matcher(trimmed).matches();
            if (trimmed.isEmpty() || valid) {
                return null;
            }
            return "must be an IP address or a host name, but was: " + value;
        });
    }

    static String fitsInInt(String value) {
        final var parsed = parseLong(value);
        if (parsed == null || (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE)) {
            return null;
        }
        return "must be between " + Integer.MIN_VALUE + " and " + Integer.MAX_VALUE + ", but was: " + value;
    }

    private static boolean isAddressLiteral(String value) {
        try {
            return InetAddress.ofLiteral(value) != null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
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
