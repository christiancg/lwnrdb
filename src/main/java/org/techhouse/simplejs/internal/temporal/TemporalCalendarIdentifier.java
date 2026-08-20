package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * ISO8601-only {@code CanonicalizeCalendar} / {@code ParseTemporalCalendarString}: the only builtin
 * calendar this engine supports is {@code "iso8601"}, matched ASCII-case-insensitively (spec's
 * {@code IsBuiltinCalendar} is an ASCII fold, not a locale-sensitive one - {@code String#toLowerCase}
 * would mis-handle the Turkish dotted capital I, so the comparison is done manually per character).
 */
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

    // Constructor positional argument / withCalendar: a bare calendar identifier only, no ISO
    // date-time-string extraction fallback (per test262, a full ISO string - even a validly-formed
    // one - is rejected here even though the property-bag `calendar` field accepts it).
    public static String canonicalizeBare(String id) {
        if (!isBuiltin(id)) {
            throw new RangeErrorException("Only the \"iso8601\" calendar is supported by this engine, got: " + id);
        }
        return "iso8601";
    }

    // Property-bag `calendar` field: accepts either a bare identifier or a full ISO
    // date/date-time/year-month/month-day string, extracting (or defaulting to iso8601) its
    // u-ca annotation.
    public static String canonicalizeFlexible(String id) {
        if (isBuiltin(id)) {
            return "iso8601";
        }
        final var extracted = tryExtractAnnotation(id);
        if (extracted == null || !isBuiltin(extracted)) {
            throw new RangeErrorException("Invalid calendar identifier: " + id);
        }
        return "iso8601";
    }

    private static String tryExtractAnnotation(String id) {
        try {
            return orDefault(TemporalParser.parseDate(id).calendar());
        } catch (RangeErrorException ignored) {
            // fall through to the next production
        }
        try {
            return orDefault(TemporalParser.parseYearMonth(id).calendar());
        } catch (RangeErrorException ignored) {
            // fall through to the next production
        }
        try {
            return orDefault(TemporalParser.parseMonthDay(id).calendar());
        } catch (RangeErrorException ignored) {
            // fall through to the next production
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
