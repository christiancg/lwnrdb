package org.techhouse.simplejs.builtins.date;

import static org.techhouse.simplejs.builtins.DateBuiltins.MAX_TIME;
import static org.techhouse.simplejs.builtins.DateBuiltins.MAX_YEAR;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.MS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.MS_PER_HOUR;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.MS_PER_MINUTE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.MS_PER_SECOND;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import org.techhouse.simplejs.builtins.InterpreterOps;

public final class DateArithmetic {
    public static double makeDay(double year, double month, double date) {
        if (!Double.isFinite(year) || !Double.isFinite(month) || !Double.isFinite(date)) {
            return Double.NaN;
        }
        final var y = truncate(year);
        final var m = truncate(month);
        final var ym = y + Math.floor(m / 12);
        if (Math.abs(ym) > MAX_YEAR) {
            return Double.NaN;
        }
        final var mn = (int) (m - Math.floor(m / 12) * 12);
        return java.time.LocalDate.of((int) ym, mn + 1, 1).toEpochDay() + truncate(date) - 1;
    }

    public static double makeTime(double hour, double minute, double second, double millis) {
        if (!Double.isFinite(hour) || !Double.isFinite(minute) || !Double.isFinite(second)
                || !Double.isFinite(millis)) {
            return Double.NaN;
        }
        return truncate(hour) * MS_PER_HOUR + truncate(minute) * MS_PER_MINUTE + truncate(second) * MS_PER_SECOND
                + truncate(millis);
    }

    public static double makeDate(double day, double time) {
        if (!Double.isFinite(day) || !Double.isFinite(time)) {
            return Double.NaN;
        }
        final var result = day * MS_PER_DAY + time;
        return Double.isFinite(result) ? result : Double.NaN;
    }

    public static double timeClip(double time) {
        if (!Double.isFinite(time) || Math.abs(time) > MAX_TIME) {
            return Double.NaN;
        }
        return truncate(time) + 0d;
    }

    public static double truncate(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        return value < 0 ? Math.ceil(value) : Math.floor(value);
    }

    public static ZoneRules zoneRules(InterpreterOps ops) {
        return InterpreterOps.timeZone(ops).getRules();
    }

    public static double localOffset(double utcTime, InterpreterOps ops) {
        if (!Double.isFinite(utcTime) || Math.abs(utcTime) > MAX_TIME) {
            return 0;
        }
        return zoneRules(ops).getOffset(Instant.ofEpochMilli((long) utcTime)).getTotalSeconds() * MS_PER_SECOND;
    }

    public static double localTime(double utcTime, InterpreterOps ops) {
        return Double.isNaN(utcTime) ? utcTime : utcTime + localOffset(utcTime, ops);
    }

    public static double utcFromLocal(double localTimeValue, InterpreterOps ops) {
        if (!Double.isFinite(localTimeValue) || Math.abs(localTimeValue) > MAX_TIME + MS_PER_DAY) {
            return localTimeValue;
        }
        final var millis = (long) localTimeValue;
        final var local = LocalDateTime.ofEpochSecond(Math.floorDiv(millis, 1000L),
                (int) (Math.floorMod(millis, 1000L) * 1_000_000L), ZoneOffset.UTC);
        final var rules = zoneRules(ops);
        final var offsets = rules.getValidOffsets(local);
        final var offset = offsets.isEmpty() ? rules.getOffset(local) : offsets.getFirst();
        return localTimeValue - offset.getTotalSeconds() * MS_PER_SECOND;
    }

    public static double day(double t) {
        return Math.floor(t / MS_PER_DAY);
    }

    public static double timeWithinDay(double t) {
        return t - day(t) * MS_PER_DAY;
    }

    public static double hourFromTime(double t) {
        return floorMod(Math.floor(t / MS_PER_HOUR), 24);
    }

    public static double minFromTime(double t) {
        return floorMod(Math.floor(t / MS_PER_MINUTE), 60);
    }

    public static double secFromTime(double t) {
        return floorMod(Math.floor(t / MS_PER_SECOND), 60);
    }

    public static double msFromTime(double t) {
        return floorMod(t, MS_PER_SECOND);
    }

    public static double yearFromTime(double t) {
        return java.time.LocalDate.ofEpochDay((long) day(t)).getYear();
    }

    public static double monthFromTime(double t) {
        return java.time.LocalDate.ofEpochDay((long) day(t)).getMonthValue() - 1d;
    }

    public static double dateFromTime(double t) {
        return java.time.LocalDate.ofEpochDay((long) day(t)).getDayOfMonth();
    }

    public static double weekDay(double t) {
        return floorMod(day(t) + 4, 7);
    }

    public static double floorMod(double value, double modulus) {
        return value - Math.floor(value / modulus) * modulus;
    }

    private DateArithmetic() {
    }
}
