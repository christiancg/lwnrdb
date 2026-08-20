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

    private static void requireCalendarIndependent(DurationFields fields, Unit unit) {
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

    private static BigInteger totalNanoseconds(DurationFields fields) {
        return BigInteger.valueOf((long) fields.days()).multiply(NANOS_PER_DAY)
                .add(BigInteger.valueOf((long) fields.hours()).multiply(NANOS_PER_HOUR))
                .add(BigInteger.valueOf((long) fields.minutes()).multiply(NANOS_PER_MINUTE))
                .add(BigInteger.valueOf((long) fields.seconds()).multiply(NANOS_PER_SECOND))
                .add(BigInteger.valueOf((long) fields.milliseconds()).multiply(NANOS_PER_MILLI))
                .add(BigInteger.valueOf((long) fields.microseconds()).multiply(NANOS_PER_MICRO))
                .add(BigInteger.valueOf((long) fields.nanoseconds()));
    }

    private static BigInteger nanosPerUnit(Unit unit) {
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

    private static DurationFields negate(DurationFields fields) {
        return new DurationFields(-fields.years(), -fields.months(), -fields.weeks(), -fields.days(), -fields.hours(),
                -fields.minutes(), -fields.seconds(), -fields.milliseconds(), -fields.microseconds(),
                -fields.nanoseconds());
    }

    private static BigInteger applyRounding(BigInteger signedTotal, BigInteger incrementNanos, RoundingMode mode) {
        final var dm = signedTotal.divideAndRemainder(incrementNanos);
        final var quotient = dm[0];
        final var remainder = dm[1];
        if (remainder.signum() == 0) {
            return signedTotal;
        }
        final var sign = signedTotal.signum();
        final var remainderAbs = remainder.abs();
        final var roundedQuotient = switch (mode) {
            case TRUNC -> quotient;
            case CEIL -> sign > 0 ? quotient.add(BigInteger.ONE) : quotient;
            case FLOOR -> sign < 0 ? quotient.subtract(BigInteger.ONE) : quotient;
            case HALF_EXPAND -> isAtLeastHalf(remainderAbs, incrementNanos) ? awayFromZero(quotient, sign) : quotient;
            case HALF_TRUNC -> isMoreThanHalf(remainderAbs, incrementNanos) ? awayFromZero(quotient, sign) : quotient;
            case HALF_CEIL -> halfDirectional(quotient, remainderAbs, incrementNanos, sign, true);
            case HALF_FLOOR -> halfDirectional(quotient, remainderAbs, incrementNanos, sign, false);
            case HALF_EVEN -> halfEven(quotient, remainderAbs, incrementNanos, sign);
        };
        return roundedQuotient.multiply(incrementNanos);
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
