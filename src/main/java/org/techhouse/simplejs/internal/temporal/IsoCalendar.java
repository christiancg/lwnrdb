package org.techhouse.simplejs.internal.temporal;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class IsoCalendar {
    private static final int[] DAYS_IN_MONTH = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    private static final WeekFields ISO_WEEK_FIELDS = WeekFields.ISO;

    private IsoCalendar() {
    }

    public static boolean isLeapYear(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    public static int daysInMonth(int year, int month) {
        if (month == 2 && isLeapYear(year)) {
            return 29;
        }
        return DAYS_IN_MONTH[month - 1];
    }

    public static int daysInYear(int year) {
        return isLeapYear(year) ? 366 : 365;
    }

    public static Iso8601Fields regulateDate(int year, int month, int day, RegulateOverflow overflow) {
        if (overflow == RegulateOverflow.CONSTRAIN) {
            final var constrainedMonth = Math.clamp(month, 1, 12);
            final var constrainedDay = Math.clamp(day, 1, daysInMonth(year, constrainedMonth));
            return new Iso8601Fields(year, constrainedMonth, constrainedDay);
        }
        if (month < 1 || month > 12) {
            throw new RangeErrorException("month must be in the range 1..12, got " + month);
        }
        final var maxDay = daysInMonth(year, month);
        if (day < 1 || day > maxDay) {
            throw new RangeErrorException("day must be in the range 1.." + maxDay + ", got " + day);
        }
        return new Iso8601Fields(year, month, day);
    }

    /**
     * Carries month overflow/underflow into year and day overflow/underflow into month/year, e.g.
     * (2023, 13, 45) balances into a valid calendar date. Delegates to {@link LocalDate} since
     * Temporal's "iso8601" calendar is exactly the proleptic Gregorian calendar java.time already
     * implements, rather than reimplementing epoch-day arithmetic.
     */
    public static Iso8601Fields balanceIsoDate(long year, long month, long day) {
        try {
            final var firstOfMonth = LocalDate.of(Math.toIntExact(year), 1, 1).plusMonths(month - 1);
            final var resolved = firstOfMonth.plusDays(day - 1);
            return new Iso8601Fields(resolved.getYear(), resolved.getMonthValue(), resolved.getDayOfMonth());
        } catch (ArithmeticException | DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range");
        }
    }

    public static int compareIsoDate(Iso8601Fields a, Iso8601Fields b) {
        if (a.year() != b.year()) {
            return Integer.compare(a.year(), b.year());
        }
        if (a.month() != b.month()) {
            return Integer.compare(a.month(), b.month());
        }
        return Integer.compare(a.day(), b.day());
    }

    /** ISO 8601 day of week: 1 = Monday .. 7 = Sunday. */
    public static int dayOfWeek(Iso8601Fields date) {
        return toLocalDate(date).getDayOfWeek().getValue();
    }

    public static int dayOfYear(Iso8601Fields date) {
        return toLocalDate(date).getDayOfYear();
    }

    /** ISO 8601 week-numbering week: week 1 is the week containing the year's first Thursday. */
    public static int weekOfYear(Iso8601Fields date) {
        return toLocalDate(date).get(ISO_WEEK_FIELDS.weekOfWeekBasedYear());
    }

    public static int yearOfWeek(Iso8601Fields date) {
        return toLocalDate(date).get(ISO_WEEK_FIELDS.weekBasedYear());
    }

    private static LocalDate toLocalDate(Iso8601Fields date) {
        try {
            return LocalDate.of(date.year(), date.month(), date.day());
        } catch (DateTimeException e) {
            throw new RangeErrorException("invalid ISO date: " + date);
        }
    }

    /**
     * AddISODate: adds a calendar-aware years/months delta first (regulating the day against the
     * resulting year/month via {@code overflow}), then balances in the day-granular weeks/days
     * delta. The two-phase order matters - balancing everything through {@link #balanceIsoDate} in
     * one shot would resolve e.g. "Jan 31 + 1 month" by walking 31 raw days from Feb 1st instead of
     * constraining/rejecting against February's real length.
     */
    public static Iso8601Fields addDate(Iso8601Fields date, double years, double months, double weeks, double days,
            RegulateOverflow overflow) {
        final long totalMonths = (date.month() - 1L) + (long) months;
        final long yearCarry = Math.floorDiv(totalMonths, 12);
        final int balancedMonth = (int) Math.floorMod(totalMonths, 12) + 1;
        final long balancedYear = date.year() + (long) years + yearCarry;
        final int intYear;
        try {
            intYear = Math.toIntExact(balancedYear);
        } catch (ArithmeticException e) {
            throw new RangeErrorException("date value is outside the representable range");
        }
        final var regulated = regulateDate(intYear, balancedMonth, date.day(), overflow);
        return balanceIsoDate(regulated.year(), regulated.month(), regulated.day() + (long) weeks * 7 + (long) days);
    }

    /**
     * DifferenceISODate: the calendar difference from {@code date1} to {@code date2} (i.e. the
     * duration that {@link #addDate} would apply to {@code date1} to reach {@code date2}), broken
     * down greedily into units no larger than {@code largestUnit}. Delegates to {@link Period}
     * (year/month) since that is the same greedy proleptic-Gregorian breakdown the "iso8601"
     * calendar's date arithmetic already reduces to.
     */
    public static DurationFields differenceISODate(Iso8601Fields date1, Iso8601Fields date2, Unit largestUnit) {
        final var start = toLocalDate(date1);
        final var end = toLocalDate(date2);
        if (start.equals(end)) {
            return DurationFields.ZERO;
        }
        final var sign = start.isBefore(end) ? 1 : -1;
        final var smaller = sign > 0 ? start : end;
        final var larger = sign > 0 ? end : start;
        return switch (largestUnit) {
            case YEAR -> {
                final var period = Period.between(smaller, larger);
                yield new DurationFields(sign * (double) period.getYears(), sign * (double) period.getMonths(), 0,
                        sign * (double) period.getDays(), 0, 0, 0, 0, 0, 0);
            }
            case MONTH -> {
                final var period = Period.between(smaller, larger);
                final var months = period.getYears() * 12L + period.getMonths();
                yield new DurationFields(0, sign * (double) months, 0, sign * (double) period.getDays(), 0, 0, 0, 0, 0,
                        0);
            }
            case WEEK -> {
                final var totalDays = ChronoUnit.DAYS.between(smaller, larger);
                final long weeks = totalDays / 7;
                final long remainderDays = totalDays % 7;
                yield new DurationFields(0, 0, sign * (double) weeks, sign * (double) remainderDays, 0, 0, 0, 0, 0, 0);
            }
            default ->
                new DurationFields(0, 0, 0, sign * (double) ChronoUnit.DAYS.between(smaller, larger), 0, 0, 0, 0, 0, 0);
        };
    }
}
