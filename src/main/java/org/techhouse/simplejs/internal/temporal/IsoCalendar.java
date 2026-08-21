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
        final Iso8601Fields result;
        if (overflow == RegulateOverflow.CONSTRAIN) {
            final var constrainedMonth = Math.clamp(month, 1, 12);
            final var constrainedDay = Math.clamp(day, 1, daysInMonth(year, constrainedMonth));
            result = new Iso8601Fields(year, constrainedMonth, constrainedDay);
        } else {
            if (month < 1 || month > 12) {
                throw new RangeErrorException("month must be in the range 1..12, got " + month);
            }
            final var maxDay = daysInMonth(year, month);
            if (day < 1 || day > maxDay) {
                throw new RangeErrorException("day must be in the range 1.." + maxDay + ", got " + day);
            }
            result = new Iso8601Fields(year, month, day);
        }
        requireWithinRepresentableRange(result);
        return result;
    }

    // ISODateWithinLimits: every ISO date type (PlainDate, and by extension PlainDateTime/
    // ZonedDateTime's date part) is representable only within +-10**8 days of the epoch, with one
    // extra day of headroom on the negative side (matching the exact published PlainDate range
    // -271821-04-19 .. +275760-09-13). Applied here - the single choke point every regulated/balanced
    // ISO date passes through - rather than duplicated per Temporal type.
    private static final long EPOCH_DAYS_LIMIT = 100_000_000L;

    private static void requireWithinRepresentableRange(Iso8601Fields date) {
        final long epochDay;
        try {
            epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        } catch (DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range");
        }
        if (epochDay < -EPOCH_DAYS_LIMIT - 1 || epochDay > EPOCH_DAYS_LIMIT) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
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
            final var result = new Iso8601Fields(resolved.getYear(), resolved.getMonthValue(),
                    resolved.getDayOfMonth());
            requireWithinRepresentableRange(result);
            return result;
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
     * down greedily into units no larger than {@code largestUnit}. The week/day cases delegate to
     * {@link ChronoUnit#DAYS} (an exact, direction-symmetric day count - negating it is always
     * correct). The year/month cases are NOT simply {@link Period#between} on the smaller/larger pair
     * negated when {@code date1 > date2}: month length varies, so a period computed forward from the
     * earlier date and then negated does not generally land back on the later date when re-applied
     * from it (e.g. the month-end/leap-day boundary cases {@link #monthDayDifference} exists to get
     * right) - {@link #monthDayDifference} instead searches directly from {@code date1} in the actual
     * sign direction, verified against {@link LocalDate#plusMonths} (the same CONSTRAIN semantics
     * {@link #addDate} uses), so the result always round-trips via {@link #addDate}.
     */
    public static DurationFields differenceISODate(Iso8601Fields date1, Iso8601Fields date2, Unit largestUnit) {
        final var start = toLocalDate(date1);
        final var end = toLocalDate(date2);
        if (start.equals(end)) {
            return DurationFields.ZERO;
        }
        return switch (largestUnit) {
            case YEAR -> {
                final var diff = monthDayDifference(start, end);
                final var years = diff.totalMonths() / 12;
                final var months = diff.totalMonths() % 12;
                yield new DurationFields(years, months, 0, diff.days(), 0, 0, 0, 0, 0, 0);
            }
            case MONTH -> {
                final var diff = monthDayDifference(start, end);
                yield new DurationFields(0, diff.totalMonths(), 0, diff.days(), 0, 0, 0, 0, 0, 0);
            }
            case WEEK -> {
                final var sign = start.isBefore(end) ? 1 : -1;
                final var smaller = sign > 0 ? start : end;
                final var larger = sign > 0 ? end : start;
                final var totalDays = ChronoUnit.DAYS.between(smaller, larger);
                final long weeks = totalDays / 7;
                final long remainderDays = totalDays % 7;
                yield new DurationFields(0, 0, signed(sign, weeks), signed(sign, remainderDays), 0, 0, 0, 0, 0, 0);
            }
            default -> {
                final var sign = start.isBefore(end) ? 1 : -1;
                final var smaller = sign > 0 ? start : end;
                final var larger = sign > 0 ? end : start;
                yield new DurationFields(0, 0, 0, signed(sign, ChronoUnit.DAYS.between(smaller, larger)), 0, 0, 0, 0, 0,
                        0);
            }
        };
    }

    private record MonthDayDiff(long totalMonths, long days) {
    }

    // Searches for the whole-month count (signed, anchored at `start`) such that start.plusMonths(...)
    // lands as close to `end` as possible without passing it (in the actual start->end direction),
    // then measures the exact day remainder from that landing point - the direct, round-trip-safe
    // replacement for a smaller/larger Period.between negated post hoc.
    private static MonthDayDiff monthDayDifference(LocalDate start, LocalDate end) {
        final var sign = start.isBefore(end) ? 1 : -1;
        var totalMonths = (end.getYear() - start.getYear()) * 12L + (end.getMonthValue() - start.getMonthValue());
        while (overshoots(start.plusMonths(totalMonths), end, sign)) {
            totalMonths -= sign;
        }
        while (!overshoots(start.plusMonths(totalMonths + sign), end, sign)) {
            totalMonths += sign;
        }
        final var landing = start.plusMonths(totalMonths);
        return new MonthDayDiff(totalMonths, ChronoUnit.DAYS.between(landing, end));
    }

    // True once `candidate` has gone past `target` in the `sign` direction of travel.
    private static boolean overshoots(LocalDate candidate, LocalDate target, int sign) {
        return sign > 0 ? candidate.isAfter(target) : candidate.isBefore(target);
    }

    // A zero component must stay +0 after applying `sign`, not become -0 (a Duration field is never
    // observably signed-zero per spec) - plain `sign * value` produces -0 in IEEE 754 whenever value
    // is 0 and sign is negative.
    private static double signed(int sign, long value) {
        return value == 0 ? 0.0 : sign * (double) value;
    }
}
