package org.techhouse.simplejs.internal.temporal;

import java.math.BigInteger;
import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * Sign determination, balancing and rounding shared by every Temporal type's {@code round}/
 * {@code since}/{@code until}. Balancing/rounding across years, months or weeks is genuinely
 * calendar-dependent (a "month" has no fixed length) and needs a {@code relativeTo} date to
 * resolve — that lands with the calendar-aware types in a later phase. This class only implements
 * the calendar-independent part: time units up to and including days, where a "day" is always
 * exactly 86,400 seconds (a {@code Duration} is not anchored to a real calendar date, so there is
 * no DST to account for).
 */
public final class DurationMath {
    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger NANOS_PER_HOUR = BigInteger.valueOf(3_600_000_000_000L);
    private static final BigInteger NANOS_PER_MINUTE = BigInteger.valueOf(60_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);
    private static final BigInteger NANOS_PER_MILLI = BigInteger.valueOf(1_000_000L);
    private static final BigInteger NANOS_PER_MICRO = BigInteger.valueOf(1_000L);
    private static final BigInteger TWO = BigInteger.valueOf(2);

    private DurationMath() {
    }

    public static int sign(DurationFields fields) {
        var sign = 0;
        for (final var value : allFields(fields)) {
            if (value > 0) {
                if (sign < 0) {
                    throw new RangeErrorException("Duration fields must all have the same sign, or be zero");
                }
                sign = 1;
            } else if (value < 0) {
                if (sign > 0) {
                    throw new RangeErrorException("Duration fields must all have the same sign, or be zero");
                }
                sign = -1;
            }
        }
        return sign;
    }

    public static DurationFields balanceDuration(DurationFields fields, Unit largestUnit) {
        requireCalendarIndependent(fields, largestUnit);
        final var overallSign = sign(fields);
        if (overallSign == 0) {
            return DurationFields.ZERO;
        }
        final var totalAbs = totalNanoseconds(fields).abs();
        final var decomposed = decompose(totalAbs, largestUnit);
        return overallSign < 0 ? negate(decomposed) : decomposed;
    }

    // Unlike balanceDuration (which re-normalizes an already-valid, uniform-sign Duration into a
    // different largestUnit), this starts from an already-summed signed total - the shape AddDurations
    // needs, since a raw per-field sum of two valid durations (e.g. {days:1} + {hours:-1}) can
    // legitimately have mixed per-field signs before it is carried/decomposed, which sign(fields)
    // would otherwise reject.
    public static DurationFields balanceFromTotalNanoseconds(BigInteger totalNanoseconds, Unit largestUnit) {
        if (largestUnit.isLargerThan(Unit.DAY)) {
            throw new UnsupportedOperationException(
                    largestUnit + ": year/month/week duration balancing is calendar-dependent (needs a relativeTo "
                            + "date) and is not implemented in this phase");
        }
        if (totalNanoseconds.signum() == 0) {
            return DurationFields.ZERO;
        }
        final var decomposed = decompose(totalNanoseconds.abs(), largestUnit);
        return totalNanoseconds.signum() < 0 ? negate(decomposed) : decomposed;
    }

    public static DurationFields roundDuration(DurationFields fields, Unit smallestUnit, long roundingIncrement,
            RoundingMode mode, Unit largestUnit) {
        requireCalendarIndependent(fields, smallestUnit);
        requireCalendarIndependent(fields, largestUnit);
        if (roundingIncrement < 1) {
            throw new RangeErrorException("roundingIncrement must be >= 1, got " + roundingIncrement);
        }
        sign(fields);
        final var signedTotal = totalNanoseconds(fields);
        final var incrementNanos = nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(roundingIncrement));
        final var roundedSigned = applyRounding(signedTotal, incrementNanos, mode);
        final var decomposed = decompose(roundedSigned.abs(), largestUnit);
        return roundedSigned.signum() < 0 ? negate(decomposed) : decomposed;
    }

    public static void requireCalendarIndependent(DurationFields fields, Unit unit) {
        if (unit.isLargerThan(Unit.DAY)) {
            throw new UnsupportedOperationException(
                    unit + ": year/month/week duration balancing is calendar-dependent (needs a relativeTo date) "
                            + "and is not implemented in this phase");
        }
        if (fields.years() != 0 || fields.months() != 0 || fields.weeks() != 0) {
            throw new UnsupportedOperationException(
                    "year/month/week duration balancing is calendar-dependent (needs a relativeTo date) "
                            + "and is not implemented in this phase");
        }
    }

    private static double[] allFields(DurationFields fields) {
        return new double[]{fields.years(), fields.months(), fields.weeks(), fields.days(), fields.hours(),
                fields.minutes(), fields.seconds(), fields.milliseconds(), fields.microseconds(), fields.nanoseconds()};
    }

    public static BigInteger totalNanoseconds(DurationFields fields) {
        return BigInteger.valueOf((long) fields.days()).multiply(NANOS_PER_DAY)
                .add(BigInteger.valueOf((long) fields.hours()).multiply(NANOS_PER_HOUR))
                .add(BigInteger.valueOf((long) fields.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) fields.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) fields.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) fields.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) fields.nanoseconds()));
    }

    public static BigInteger nanosPerUnit(Unit unit) {
        return switch (unit) {
            case DAY -> NANOS_PER_DAY;
            case HOUR -> NANOS_PER_HOUR;
            case MINUTE -> NANOS_PER_MINUTE;
            case SECOND -> NANOS_PER_SECOND;
            case MILLISECOND -> NANOS_PER_MILLI;
            case MICROSECOND -> NANOS_PER_MICRO;
            case NANOSECOND -> BigInteger.ONE;
            default -> throw new UnsupportedOperationException(unit + " has no fixed nanosecond length");
        };
    }

    // The hours..nanoseconds portion of a duration's exact nanosecond length, deliberately excluding
    // `days` (unlike totalNanoseconds) - callers that carry a duration's date part through calendar
    // arithmetic (IsoCalendar.addDate) need the time part added separately, matching AddDateTime/
    // AddZonedDateTime's own two-phase split.
    public static BigInteger timeUnitsNanoseconds(DurationFields fields) {
        return BigInteger.valueOf((long) fields.hours()).multiply(NANOS_PER_HOUR)
                .add(BigInteger.valueOf((long) fields.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) fields.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) fields.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) fields.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) fields.nanoseconds()));
    }

    private static long nanosOfDayLong(IsoTimeFields t) {
        return t.hour() * 3_600_000_000_000L + t.minute() * 60_000_000_000L + t.second() * 1_000_000_000L
                + t.millisecond() * 1_000_000L + t.microsecond() * 1_000L + t.nanosecond();
    }

    private static DurationFields timeNanosToFields(long nanos) {
        final var sign = Long.signum(nanos);
        var abs = Math.abs(nanos);
        final var hours = abs / 3_600_000_000_000L;
        abs %= 3_600_000_000_000L;
        final var minutes = abs / 60_000_000_000L;
        abs %= 60_000_000_000L;
        final var seconds = abs / 1_000_000_000L;
        abs %= 1_000_000_000L;
        final var millis = abs / 1_000_000L;
        abs %= 1_000_000L;
        final var micros = abs / 1_000L;
        final var nanosRemainder = abs % 1_000L;
        return new DurationFields(0, 0, 0, 0, signedComponent(sign, hours), signedComponent(sign, minutes),
                signedComponent(sign, seconds), signedComponent(sign, millis), signedComponent(sign, micros),
                signedComponent(sign, nanosRemainder));
    }

    // See negateField: a zero component must stay +0 after applying `sign`, never -0.
    private static double signedComponent(int sign, long value) {
        return value == 0 ? 0.0 : sign * (double) value;
    }

    // DifferenceISODateTime: the exact (unrounded) calendar-aware difference between two date+time
    // points, decomposed no larger than largestUnit. The calendar-independent sub-day time-of-day diff
    // is computed first; if its sign disagrees with the date-only comparison, a day is borrowed so the
    // (calendar-aware) date-part breakdown and the time-part remainder end up carrying the same sign.
    // largestUnit "day" and smaller fold the whole result into a single nanosecond total instead (a
    // "day" is always exactly 86,400 seconds between two ISO-local date+time points, unlike a real
    // calendar month/week). Shared by every Temporal type's since/until (PlainDateTime, ZonedDateTime
    // on its local fields) plus the relativeTo-anchored Duration operations, rather than duplicated
    // per type.
    public static DurationFields differenceCalendar(Iso8601Fields date1, IsoTimeFields time1, Iso8601Fields date2,
            IsoTimeFields time2, Unit largestUnit) {
        final var dateSign = IsoCalendar.compareIsoDate(date2, date1);
        var rawTimeDiffNanos = nanosOfDayLong(time2) - nanosOfDayLong(time1);
        var adjustedDate2 = date2;
        if (rawTimeDiffNanos != 0 && dateSign != 0) {
            if (dateSign > 0 && rawTimeDiffNanos < 0) {
                adjustedDate2 = IsoCalendar.balanceIsoDate(date2.year(), date2.month(), date2.day() - 1L);
                rawTimeDiffNanos += NANOS_PER_DAY.longValueExact();
            } else if (dateSign < 0 && rawTimeDiffNanos > 0) {
                adjustedDate2 = IsoCalendar.balanceIsoDate(date2.year(), date2.month(), date2.day() + 1L);
                rawTimeDiffNanos -= NANOS_PER_DAY.longValueExact();
            }
        }
        final var dateLargestUnit = largestUnit.isLargerThan(Unit.DAY) ? largestUnit : Unit.DAY;
        final var dateDiff = IsoCalendar.differenceISODate(date1, adjustedDate2, dateLargestUnit);
        if (largestUnit.isLargerThan(Unit.DAY)) {
            final var timeFields = timeNanosToFields(rawTimeDiffNanos);
            return new DurationFields(dateDiff.years(), dateDiff.months(), dateDiff.weeks(), dateDiff.days(),
                    timeFields.hours(), timeFields.minutes(), timeFields.seconds(), timeFields.milliseconds(),
                    timeFields.microseconds(), timeFields.nanoseconds());
        }
        final var totalNanos = BigInteger.valueOf((long) dateDiff.days()).multiply(NANOS_PER_DAY)
                .add(BigInteger.valueOf(rawTimeDiffNanos));
        return balanceFromTotalNanoseconds(totalNanos, largestUnit);
    }

    private static DurationFields decompose(BigInteger absNanos, Unit largestUnit) {
        var remaining = absNanos;
        long days = 0;
        long hours = 0;
        long minutes = 0;
        long seconds = 0;
        long millis = 0;
        long micros = 0;
        if (Unit.DAY.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_DAY);
            days = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.HOUR.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_HOUR);
            hours = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.MINUTE.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MINUTE);
            minutes = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.SECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_SECOND);
            seconds = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.MILLISECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MILLI);
            millis = dm[0].longValueExact();
            remaining = dm[1];
        }
        if (Unit.MICROSECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MICRO);
            micros = dm[0].longValueExact();
            remaining = dm[1];
        }
        final var nanos = remaining.longValueExact();
        return new DurationFields(0, 0, 0, days, hours, minutes, seconds, millis, micros, nanos);
    }

    // Negating a zero field must stay +0, not become -0 (a Duration's fields are never observably
    // signed-zero per spec) - shared publicly so every Temporal type's since()/add(-x)/etc. negation
    // gets this for free instead of re-deriving it.
    public static DurationFields negate(DurationFields fields) {
        return new DurationFields(negateField(fields.years()), negateField(fields.months()),
                negateField(fields.weeks()), negateField(fields.days()), negateField(fields.hours()),
                negateField(fields.minutes()), negateField(fields.seconds()), negateField(fields.milliseconds()),
                negateField(fields.microseconds()), negateField(fields.nanoseconds()));
    }

    private static double negateField(double value) {
        return value == 0 ? 0.0 : -value;
    }

    private static BigInteger applyRounding(BigInteger signedTotal, BigInteger incrementNanos, RoundingMode mode) {
        final var dm = signedTotal.divideAndRemainder(incrementNanos);
        final var quotient = dm[0];
        final var remainder = dm[1];
        if (remainder.signum() == 0) {
            return signedTotal;
        }
        final var sign = signedTotal.signum();
        final var roundedQuotient = roundedQuotient(quotient, remainder.abs(), incrementNanos, sign, mode);
        return roundedQuotient.multiply(incrementNanos);
    }

    // Rounds a nanosecond total to a nanosecond-fixed increment. Public so relativeTo-anchored
    // rounding (Duration.round/total/compare with calendar units, ZonedDateTime/PlainDateTime
    // since/until above "day") can reuse this exact fixed-length rounding for their own day-and-below
    // tail, rather than reimplementing the eight-branch rounding-mode decision.
    public static BigInteger roundSignedTotalNanoseconds(BigInteger signedTotal, BigInteger incrementNanos,
            RoundingMode mode) {
        return applyRounding(signedTotal, incrementNanos, mode);
    }

    // The rounding-mode decision extracted from applyRounding so it can be reused for a variable-span
    // "increment" too: a calendar bracket (e.g. one specific month, or `roundingIncrement` months)
    // does not have a fixed nanosecond length, but once its two exact boundary instants are known, the
    // exact position of the unrounded point within that span is decided by exactly the same
    // half-vs-away-from-zero-vs-toward-zero rules as a fixed-length increment - only the meaning of
    // "quotient"/"remainder"/"denominator" changes (a bracket index instead of total/incrementNanos).
    public static BigInteger roundedQuotient(BigInteger quotient, BigInteger remainderAbs, BigInteger denominator,
            int sign, RoundingMode mode) {
        if (remainderAbs.signum() == 0) {
            return quotient;
        }
        return switch (mode) {
            case TRUNC -> quotient;
            case CEIL -> sign > 0 ? quotient.add(BigInteger.ONE) : quotient;
            case FLOOR -> sign < 0 ? quotient.subtract(BigInteger.ONE) : quotient;
            case EXPAND -> awayFromZero(quotient, sign);
            case HALF_EXPAND -> isAtLeastHalf(remainderAbs, denominator) ? awayFromZero(quotient, sign) : quotient;
            case HALF_TRUNC -> isMoreThanHalf(remainderAbs, denominator) ? awayFromZero(quotient, sign) : quotient;
            case HALF_CEIL -> halfDirectional(quotient, remainderAbs, denominator, sign, true);
            case HALF_FLOOR -> halfDirectional(quotient, remainderAbs, denominator, sign, false);
            case HALF_EVEN -> halfEven(quotient, remainderAbs, denominator, sign);
        };
    }

    private static BigInteger halfDirectional(BigInteger quotient, BigInteger remainderAbs, BigInteger incrementNanos,
            int sign, boolean tieTowardPositive) {
        if (isMoreThanHalf(remainderAbs, incrementNanos)) {
            return awayFromZero(quotient, sign);
        }
        if (isExactlyHalf(remainderAbs, incrementNanos)) {
            final var tieGoesAway = tieTowardPositive ? sign > 0 : sign < 0;
            return tieGoesAway ? awayFromZero(quotient, sign) : quotient;
        }
        return quotient;
    }

    private static BigInteger halfEven(BigInteger quotient, BigInteger remainderAbs, BigInteger incrementNanos,
            int sign) {
        if (isMoreThanHalf(remainderAbs, incrementNanos)) {
            return awayFromZero(quotient, sign);
        }
        if (isExactlyHalf(remainderAbs, incrementNanos)) {
            return quotient.mod(TWO).signum() == 0 ? quotient : awayFromZero(quotient, sign);
        }
        return quotient;
    }

    private static boolean isExactlyHalf(BigInteger remainderAbs, BigInteger incrementNanos) {
        return remainderAbs.multiply(TWO).equals(incrementNanos);
    }

    private static boolean isMoreThanHalf(BigInteger remainderAbs, BigInteger incrementNanos) {
        return remainderAbs.multiply(TWO).compareTo(incrementNanos) > 0;
    }

    private static boolean isAtLeastHalf(BigInteger remainderAbs, BigInteger incrementNanos) {
        return remainderAbs.multiply(TWO).compareTo(incrementNanos) >= 0;
    }

    private static BigInteger awayFromZero(BigInteger quotient, int sign) {
        return sign >= 0 ? quotient.add(BigInteger.ONE) : quotient.subtract(BigInteger.ONE);
    }
}
