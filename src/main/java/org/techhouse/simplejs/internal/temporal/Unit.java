package org.techhouse.simplejs.internal.temporal;

import java.util.Arrays;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public enum Unit {
    YEAR("year", "years"), MONTH("month", "months"), WEEK("week", "weeks"), DAY("day", "days"), HOUR("hour",
            "hours"), MINUTE("minute", "minutes"), SECOND("second", "seconds"), MILLISECOND("millisecond",
                    "milliseconds"), MICROSECOND("microsecond",
                            "microseconds"), NANOSECOND("nanosecond", "nanoseconds");

    public static final List<String> PLURAL_NAMES = Arrays.stream(values()).map(Unit::plural).toList();
    public static final List<String> TIME_UNIT_SINGULARS = Arrays.stream(values())
            .filter(u -> u.ordinal() >= HOUR.ordinal()).map(Unit::singular).toList();

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
