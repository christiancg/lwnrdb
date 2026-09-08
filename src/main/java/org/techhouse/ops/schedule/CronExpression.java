package org.techhouse.ops.schedule;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.Locale;
import java.util.Map;
import org.techhouse.ex.InvalidCronException;

public record CronExpression(BitSet minutes, BitSet hours, BitSet daysOfMonth, BitSet months, BitSet daysOfWeek,
        boolean domRestricted, boolean dowRestricted) {
    private static final int SEARCH_HORIZON_YEARS = 4;
    private static final Map<String, Integer> MONTH_NAMES = Map.ofEntries(Map.entry("JAN", 1), Map.entry("FEB", 2),
            Map.entry("MAR", 3), Map.entry("APR", 4), Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7),
            Map.entry("AUG", 8), Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));
    private static final Map<String, Integer> DAY_NAMES = Map.of("SUN", 0, "MON", 1, "TUE", 2, "WED", 3, "THU", 4,
            "FRI", 5, "SAT", 6);

    public static CronExpression parse(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidCronException(String.valueOf(text), "the expression is empty");
        }
        final var fields = text.trim().split("\\s+");
        if (fields.length != 5) {
            throw new InvalidCronException(text, "expected 5 fields but found " + fields.length);
        }
        final var minutes = parseField(text, fields[0], 0, 59, null);
        final var hours = parseField(text, fields[1], 0, 23, null);
        final var daysOfMonth = parseField(text, fields[2], 1, 31, null);
        final var months = parseField(text, fields[3], 1, 12, MONTH_NAMES);
        final var daysOfWeek = parseField(text, fields[4], 0, 7, DAY_NAMES);
        if (daysOfWeek.get(7)) {
            daysOfWeek.clear(7);
            daysOfWeek.set(0);
        }
        return new CronExpression(minutes, hours, daysOfMonth, months, daysOfWeek, isRestricted(fields[2]),
                isRestricted(fields[4]));
    }

    public ZonedDateTime nextAfter(ZonedDateTime from) {
        final var zone = from.getZone();
        var candidate = from.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        final var limit = candidate.plusYears(SEARCH_HORIZON_YEARS);
        while (!candidate.isAfter(limit)) {
            if (!months.get(candidate.getMonthValue())) {
                candidate = candidate.plusMonths(1).withDayOfMonth(1).truncatedTo(ChronoUnit.DAYS);
                continue;
            }
            if (!matchesDay(candidate)) {
                candidate = candidate.plusDays(1).truncatedTo(ChronoUnit.DAYS);
                continue;
            }
            if (!hours.get(candidate.getHour())) {
                candidate = candidate.plusHours(1).truncatedTo(ChronoUnit.HOURS);
                continue;
            }
            if (!minutes.get(candidate.getMinute())) {
                candidate = candidate.plusMinutes(1);
                continue;
            }
            final var resolved = candidate.atZone(zone);
            if (resolved.isAfter(from)) {
                return resolved;
            }
            candidate = candidate.plusMinutes(1);
        }
        return null;
    }

    private boolean matchesDay(LocalDateTime candidate) {
        final var domMatch = daysOfMonth.get(candidate.getDayOfMonth());
        final var dowMatch = daysOfWeek.get(candidate.getDayOfWeek().getValue() % 7);
        if (domRestricted && dowRestricted) {
            return domMatch || dowMatch;
        }
        return domMatch && dowMatch;
    }

    private static boolean isRestricted(String field) {
        return !"*".equals(field.trim());
    }

    private static BitSet parseField(String text, String field, int min, int max, Map<String, Integer> names) {
        final var bits = new BitSet(max + 2);
        for (final var part : field.trim().split(",")) {
            parsePart(text, part, min, max, names, bits);
        }
        if (bits.isEmpty()) {
            throw new InvalidCronException(text, "field '" + field + "' matches nothing");
        }
        return bits;
    }

    private static void parsePart(String text, String part, int min, int max, Map<String, Integer> names, BitSet bits) {
        if (part.isBlank()) {
            throw new InvalidCronException(text, "empty list element");
        }
        var range = part;
        var step = 1;
        final var slash = part.indexOf('/');
        if (slash >= 0) {
            range = part.substring(0, slash);
            step = parseStep(text, part.substring(slash + 1));
        }
        final int from;
        final int to;
        if ("*".equals(range)) {
            from = min;
            to = max;
        } else {
            final var dash = range.indexOf('-', 1);
            if (dash >= 0) {
                from = parseValue(text, range.substring(0, dash), min, max, names);
                to = parseValue(text, range.substring(dash + 1), min, max, names);
            } else {
                from = parseValue(text, range, min, max, names);
                to = slash >= 0 ? max : from;
            }
        }
        if (from > to) {
            throw new InvalidCronException(text, "range '" + range + "' starts after it ends");
        }
        for (var value = from; value <= to; value += step) {
            bits.set(value);
        }
    }

    private static int parseStep(String text, String raw) {
        final int step;
        try {
            step = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidCronException(text, "step '" + raw + "' is not a number");
        }
        if (step < 1) {
            throw new InvalidCronException(text, "step '" + raw + "' must be at least 1");
        }
        return step;
    }

    private static int parseValue(String text, String raw, int min, int max, Map<String, Integer> names) {
        final var token = raw.trim();
        if (token.isEmpty()) {
            throw new InvalidCronException(text, "empty value");
        }
        final int value;
        final var named = names == null ? null : names.get(token.toUpperCase(Locale.ROOT));
        if (named != null) {
            value = named;
        } else {
            try {
                value = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new InvalidCronException(text, "'" + token + "' is not a valid value");
            }
        }
        if (value < min || value > max) {
            throw new InvalidCronException(text, "'" + token + "' is outside the range " + min + "-" + max);
        }
        return value;
    }
}
