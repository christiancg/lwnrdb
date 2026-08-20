package org.techhouse.simplejs.internal.temporal;

import java.time.DateTimeException;
import java.time.LocalDate;
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
}
