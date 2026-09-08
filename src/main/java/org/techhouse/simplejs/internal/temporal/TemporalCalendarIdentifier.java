package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class TemporalCalendarIdentifier {
    private TemporalCalendarIdentifier() {
    }

    public static boolean isBuiltin(String id) {
        return asciiEqualsIgnoreCase(id, "iso8601");
    }

    public static boolean asciiEqualsIgnoreCase(String value, String lowerLiteral) {
        if (value.length() != lowerLiteral.length()) {
            return false;
        }
        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            if (c != lowerLiteral.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    public static void requireBuiltinCalendar(String id) {
        if (!isBuiltin(id)) {
            throw new RangeErrorException("Only the \"iso8601\" calendar is supported by this engine, got: " + id);
        }
    }

    public static void requireBuiltinCalendarOrAnnotated(String id) {
        if (isBuiltin(id)) {
            return;
        }
        final var extracted = tryExtractAnnotation(id);
        if (extracted == null || !isBuiltin(extracted)) {
            throw new RangeErrorException("Invalid calendar identifier: " + id);
        }
    }

    private static String tryExtractAnnotation(String id) {
        try {
            return orDefault(TemporalParser.parseDate(id).calendar());
        } catch (RangeErrorException ignored) {
        }
        try {
            return orDefault(ReducedFormParser.parseYearMonth(id).calendar());
        } catch (RangeErrorException ignored) {
        }
        try {
            return orDefault(ReducedFormParser.parseMonthDay(id).calendar());
        } catch (RangeErrorException ignored) {
        }
        try {
            return orDefault(TemporalParser.parseTime(id).calendar());
        } catch (RangeErrorException ignored) {
            return null;
        }
    }

    private static String orDefault(String calendar) {
        return calendar == null ? "iso8601" : calendar;
    }
}
