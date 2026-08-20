package org.techhouse.simplejs.internal.temporal;

import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * Ordered largest-to-smallest so duration balancing/rounding can iterate by ordinal.
 */
public enum Unit {
    YEAR("year", "years"), MONTH("month", "months"), WEEK("week", "weeks"), DAY("day", "days"), HOUR("hour",
            "hours"), MINUTE("minute", "minutes"), SECOND("second", "seconds"), MILLISECOND("millisecond",
                    "milliseconds"), MICROSECOND("microsecond",
                            "microseconds"), NANOSECOND("nanosecond", "nanoseconds");

    private final String singular;
    private final String plural;

    Unit(String singular, String plural) {
        this.singular = singular;
        this.plural = plural;
    }

    public String singular() {
        return singular;
    }

    public String plural() {
        return plural;
    }

    public boolean isLargerThan(Unit other) {
        return ordinal() < other.ordinal();
    }

    public static Unit parseTemporalUnit(String value) {
        for (final var unit : values()) {
            if (unit.singular.equals(value) || unit.plural.equals(value)) {
                return unit;
            }
        }
        throw new RangeErrorException("Invalid unit: " + value);
    }
}
