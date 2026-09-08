package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MAX_EPOCH_DAY;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_EPOCH_DAY;

import java.time.DateTimeException;
import java.time.LocalDate;
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
        final var result = regulateCalendarDate(year, month, day, overflow);
        requireWithinRepresentableRange(result);
        return result;
    }

    public static Iso8601Fields regulateCalendarDate(int year, int month, int day, RegulateOverflow overflow) {
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

    private static void requireWithinRepresentableRange(Iso8601Fields date) {
        final long epochDay;
        try {
            epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        } catch (DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range");
        }
        if (epochDay < MIN_EPOCH_DAY || epochDay > MAX_EPOCH_DAY) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
    }

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

    public static int dayOfWeek(Iso8601Fields date) {
        return toLocalDate(date).getDayOfWeek().getValue();
    }

    public static int dayOfYear(Iso8601Fields date) {
        return toLocalDate(date).getDayOfYear();
    }

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

    public static Iso8601Fields addDate(Iso8601Fields date, double years, double months, double weeks, double days,
            RegulateOverflow overflow) {
        final long totalMonths = (date.month() - 1L) + (long) months;
        final long yearCarry = Math.floorDiv(totalMonths, 12);
        final int balancedMonth = Math.floorMod(totalMonths, 12) + 1;
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

    private static boolean overshoots(LocalDate candidate, LocalDate target, int sign) {
        return sign > 0 ? candidate.isAfter(target) : candidate.isBefore(target);
    }

    private static double signed(int sign, long value) {
        return value == 0 ? 0.0 : sign * (double) value;
    }
}
