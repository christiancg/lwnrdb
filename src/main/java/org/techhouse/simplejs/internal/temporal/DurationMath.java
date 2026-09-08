package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalLimits.DURATION_DATE_LIMIT;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_HOUR;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MICRO;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MILLI;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_MINUTE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.TWO;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class DurationMath {

    private static final BigInteger MAX_TIME_DURATION_NANOS = BigInteger.TWO.pow(53)
            .multiply(BigInteger.valueOf(1_000_000_000L)).subtract(BigInteger.ONE);

    private DurationMath() {
    }

    public static void validate(DurationFields fields) {
        requireFinite(fields);
        sign(fields);
        requireValidRange(fields);
    }

    private static void requireFinite(DurationFields fields) {
        for (final var value : allFields(fields)) {
            if (!Double.isFinite(value)) {
                throw new RangeErrorException("Duration field must be finite, got " + value);
            }
        }
    }

    public static void requireValidRange(DurationFields fields) {
        if (Math.abs(fields.years()) >= DURATION_DATE_LIMIT) {
            throw new RangeErrorException("years is out of range: " + fields.years());
        }
        if (Math.abs(fields.months()) >= DURATION_DATE_LIMIT) {
            throw new RangeErrorException("months is out of range: " + fields.months());
        }
        if (Math.abs(fields.weeks()) >= DURATION_DATE_LIMIT) {
            throw new RangeErrorException("weeks is out of range: " + fields.weeks());
        }
        if (totalNanoseconds(fields).abs().compareTo(MAX_TIME_DURATION_NANOS) > 0) {
            throw new RangeErrorException("duration is out of range");
        }
    }

    @SuppressWarnings("PMD.AvoidDecimalLiteralsInBigDecimalConstructor")
    private static BigInteger exactNanos(double value, BigInteger nanosPerUnit) {
        return new BigDecimal(value).toBigInteger().multiply(nanosPerUnit);
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
        return exactNanos(fields.days(), NANOS_PER_DAY).add(exactNanos(fields.hours(), NANOS_PER_HOUR))
                .add(exactNanos(fields.minutes(), NANOS_PER_MINUTE)).add(exactNanos(fields.seconds(), NANOS_PER_SECOND))
                .add(exactNanos(fields.milliseconds(), NANOS_PER_MILLI))
                .add(exactNanos(fields.microseconds(), NANOS_PER_MICRO))
                .add(exactNanos(fields.nanoseconds(), BigInteger.ONE));
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

    public static BigInteger timeUnitsNanoseconds(DurationFields fields) {
        return exactNanos(fields.hours(), NANOS_PER_HOUR).add(exactNanos(fields.minutes(), NANOS_PER_MINUTE))
                .add(exactNanos(fields.seconds(), NANOS_PER_SECOND))
                .add(exactNanos(fields.milliseconds(), NANOS_PER_MILLI))
                .add(exactNanos(fields.microseconds(), NANOS_PER_MICRO))
                .add(exactNanos(fields.nanoseconds(), BigInteger.ONE));
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

    private static double signedComponent(int sign, long value) {
        return value == 0 ? 0.0 : sign * (double) value;
    }

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
        double days = 0;
        double hours = 0;
        double minutes = 0;
        double seconds = 0;
        double millis = 0;
        double micros = 0;
        if (Unit.DAY.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_DAY);
            days = dm[0].doubleValue();
            remaining = dm[1];
        }
        if (Unit.HOUR.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_HOUR);
            hours = dm[0].doubleValue();
            remaining = dm[1];
        }
        if (Unit.MINUTE.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MINUTE);
            minutes = dm[0].doubleValue();
            remaining = dm[1];
        }
        if (Unit.SECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_SECOND);
            seconds = dm[0].doubleValue();
            remaining = dm[1];
        }
        if (Unit.MILLISECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MILLI);
            millis = dm[0].doubleValue();
            remaining = dm[1];
        }
        if (Unit.MICROSECOND.ordinal() >= largestUnit.ordinal()) {
            final var dm = remaining.divideAndRemainder(NANOS_PER_MICRO);
            micros = dm[0].doubleValue();
            remaining = dm[1];
        }
        final var nanos = remaining.doubleValue();
        return new DurationFields(0, 0, 0, days, hours, minutes, seconds, millis, micros, nanos);
    }

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

    public static BigInteger roundSignedTotalNanoseconds(BigInteger signedTotal, BigInteger incrementNanos,
            RoundingMode mode) {
        return applyRounding(signedTotal, incrementNanos, mode);
    }

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
