package org.techhouse.config;

import java.util.regex.Pattern;

public class SizeParser {
    private static final Pattern SIZE_PATTERN =
            Pattern.compile("^\\s*(-?\\d+)\\s*([a-zA-Z]*)\\s*$");

    public static long parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Size value is null");
        }
        final var matcher = SIZE_PATTERN.matcher(input);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid size value: " + input);
        }
        final var numericPart = Long.parseLong(matcher.group(1));
        final var unitPart = matcher.group(2).toLowerCase();
        if (numericPart < 0) {
            if (numericPart == -1L && unitPart.isEmpty()) {
                return -1L;
            }
            throw new IllegalArgumentException("Negative sizes are not allowed (except -1): " + input);
        }
        if (unitPart.isEmpty() || unitPart.equals("b")) {
            return numericPart;
        }
        final var multiplier = switch (unitPart) {
            case "kb" -> 1024L;
            case "mb" -> 1024L * 1024L;
            case "gb" -> 1024L * 1024L * 1024L;
            case "tb" -> 1024L * 1024L * 1024L * 1024L;
            default -> throw new IllegalArgumentException("Unknown size unit: " + unitPart);
        };
        return numericPart * multiplier;
    }
}
