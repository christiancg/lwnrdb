package org.techhouse.simplejs.internal.temporal;

import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MAX_EPOCH_NANOS;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MAX_ISO_DATE;
import static org.techhouse.simplejs.internal.temporal.TemporalLimits.MIN_ISO_DATE;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_DAY;
import static org.techhouse.simplejs.internal.temporal.TimeUnits.NANOS_PER_SECOND;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.techhouse.simplejs.exceptions.RangeErrorException;

public final class RelativeDurationMath {

    public record Anchor(Iso8601Fields date, IsoTimeFields time, ZoneId zone) {
        public static Anchor plain(Iso8601Fields date, IsoTimeFields time) {
            return new Anchor(date, time, null);
        }

        public static Anchor zoned(Iso8601Fields date, IsoTimeFields time, ZoneId zone) {
            return new Anchor(date, time, zone);
        }

        public boolean isZoned() {
            return zone != null;
        }
    }

    public record DateTimePoint(Iso8601Fields date, IsoTimeFields time) {
    }

    private RelativeDurationMath() {
    }

    public static BigInteger toEpochNanos(Anchor anchor, Iso8601Fields date, IsoTimeFields time) {
        if (!anchor.isZoned()) {
            if (IsoCalendar.compareIsoDate(date, MIN_ISO_DATE) < 0
                    || IsoCalendar.compareIsoDate(date, MAX_ISO_DATE) > 0) {
                throw new RangeErrorException("date value is outside the representable range: " + date);
            }
            return plainEpochNanos(date, time);
        }
        final var nanos = resolveZonedEpochNanos(date, time, anchor.zone());
        if (nanos.abs().compareTo(MAX_EPOCH_NANOS) > 0) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
        return nanos;
    }

    public static BigInteger toEpochNanosUnbounded(Anchor anchor, Iso8601Fields date, IsoTimeFields time) {
        return anchor.isZoned() ? resolveZonedEpochNanos(date, time, anchor.zone()) : plainEpochNanos(date, time);
    }

    private static BigInteger plainEpochNanos(Iso8601Fields date, IsoTimeFields time) {
        final long epochDay;
        try {
            epochDay = LocalDate.of(date.year(), date.month(), date.day()).toEpochDay();
        } catch (DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range");
        }
        return BigInteger.valueOf(epochDay).multiply(NANOS_PER_DAY).add(BigInteger.valueOf(nanosOfDay(time)));
    }

    private static BigInteger resolveZonedEpochNanos(Iso8601Fields date, IsoTimeFields time, ZoneId zone) {
        final var local = toLocalDateTime(date, time);
        final var transition = zone.getRules().getTransition(local);
        final var zdt = transition == null
                ? local.atZone(zone)
                : local.toInstant(transition.getOffsetBefore()).atZone(zone);
        final var instant = zdt.toInstant();
        return BigInteger.valueOf(instant.getEpochSecond()).multiply(NANOS_PER_SECOND)
                .add(BigInteger.valueOf(instant.getNano()));
    }

    private static LocalDateTime toLocalDateTime(Iso8601Fields date, IsoTimeFields time) {
        final var nanoOfSecond = time.millisecond() * 1_000_000 + time.microsecond() * 1_000 + time.nanosecond();
        try {
            return LocalDateTime.of(date.year(), date.month(), date.day(), time.hour(), time.minute(), time.second(),
                    nanoOfSecond);
        } catch (DateTimeException e) {
            throw new RangeErrorException("Invalid date/time for relativeTo: " + e.getMessage());
        }
    }

    public static DateTimePoint fromEpochNanos(Anchor anchor, BigInteger epochNanos) {
        return anchor.isZoned() ? fromZonedEpochNanos(anchor.zone(), epochNanos) : fromPlainEpochNanos(epochNanos);
    }

    private static DateTimePoint fromPlainEpochNanos(BigInteger epochNanos) {
        final var epochDay = floorDiv(epochNanos, NANOS_PER_DAY);
        final var nanosOfDay = epochNanos.subtract(epochDay.multiply(NANOS_PER_DAY));
        final LocalDate date;
        try {
            date = LocalDate.ofEpochDay(epochDay.longValueExact());
        } catch (ArithmeticException | DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range");
        }
        return new DateTimePoint(new Iso8601Fields(date.getYear(), date.getMonthValue(), date.getDayOfMonth()),
                fromNanosOfDay(nanosOfDay.longValueExact()));
    }

    private static DateTimePoint fromZonedEpochNanos(ZoneId zone, BigInteger epochNanos) {
        final var seconds = floorDiv(epochNanos, NANOS_PER_SECOND);
        final var nanoAdjustment = epochNanos.subtract(seconds.multiply(NANOS_PER_SECOND));
        final Instant instant;
        try {
            instant = Instant.ofEpochSecond(seconds.longValueExact(), nanoAdjustment.longValueExact());
        } catch (ArithmeticException | DateTimeException e) {
            throw new RangeErrorException("date value is outside the representable range");
        }
        final var zdt = instant.atZone(zone);
        final var nanoOfSecond = zdt.getNano();
        return new DateTimePoint(new Iso8601Fields(zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth()),
                new IsoTimeFields(zdt.getHour(), zdt.getMinute(), zdt.getSecond(), nanoOfSecond / 1_000_000,
                        (nanoOfSecond / 1_000) % 1_000, nanoOfSecond % 1_000));
    }

    private static BigInteger floorDiv(BigInteger value, BigInteger divisor) {
        return value.subtract(value.mod(divisor)).divide(divisor);
    }

    private static long nanosOfDay(IsoTimeFields t) {
        return t.hour() * 3_600_000_000_000L + t.minute() * 60_000_000_000L + t.second() * 1_000_000_000L
                + t.millisecond() * 1_000_000L + t.microsecond() * 1_000L + t.nanosecond();
    }

    private static IsoTimeFields fromNanosOfDay(long nanos) {
        var remaining = nanos;
        final var hour = (int) (remaining / 3_600_000_000_000L);
        remaining %= 3_600_000_000_000L;
        final var minute = (int) (remaining / 60_000_000_000L);
        remaining %= 60_000_000_000L;
        final var second = (int) (remaining / 1_000_000_000L);
        remaining %= 1_000_000_000L;
        final var millisecond = (int) (remaining / 1_000_000L);
        remaining %= 1_000_000L;
        final var microsecond = (int) (remaining / 1_000L);
        final var nanosecond = (int) (remaining % 1_000L);
        return new IsoTimeFields(hour, minute, second, millisecond, microsecond, nanosecond);
    }

    public static DateTimePoint applyDuration(Anchor anchor, DurationFields duration, RegulateOverflow overflow) {
        if (!anchor.isZoned()) {
            final var nanosOfDay = BigInteger.valueOf(nanosOfDay(anchor.time()));
            final var total = nanosOfDay.add(DurationMath.timeUnitsNanoseconds(duration));
            final var dayCarry = floorDiv(total, NANOS_PER_DAY);
            final var newNanosOfDay = total.subtract(dayCarry.multiply(NANOS_PER_DAY));
            final var newDate = IsoCalendar.addDate(anchor.date(), duration.years(), duration.months(),
                    duration.weeks(), duration.days() + dayCarry.doubleValue(), overflow);
            return new DateTimePoint(newDate, fromNanosOfDay(newNanosOfDay.longValueExact()));
        }
        final var hasDatePart = duration.years() != 0 || duration.months() != 0 || duration.weeks() != 0
                || duration.days() != 0;
        final var dateAfterCalendar = hasDatePart
                ? IsoCalendar.addDate(anchor.date(), duration.years(), duration.months(), duration.weeks(),
                        duration.days(), overflow)
                : anchor.date();
        final var intermediateNanos = toEpochNanos(anchor, dateAfterCalendar, anchor.time());
        final var finalNanos = intermediateNanos.add(DurationMath.timeUnitsNanoseconds(duration));
        return fromEpochNanos(anchor, finalNanos);
    }

    public static DurationFields roundedDifference(Anchor anchor, Iso8601Fields endDate, IsoTimeFields endTime,
            Unit largestUnit, Unit smallestUnit, long increment, RoundingMode mode) {
        final var anchorNanos = toEpochNanos(anchor, anchor.date(), anchor.time());
        final var endNanos = toEpochNanos(anchor, endDate, endTime);
        final var zonedSubDay = anchor.isZoned() && !smallestUnit.isLargerThan(Unit.DAY) && smallestUnit != Unit.DAY
                && largestUnit == Unit.DAY;
        if (zonedSubDay) {
            final var nextDay = IsoCalendar.addDate(anchor.date(), 0, 0, 0, 1, RegulateOverflow.CONSTRAIN);
            toEpochNanos(anchor, nextDay, anchor.time());
        }
        if (endNanos.compareTo(anchorNanos) == 0) {
            return DurationFields.ZERO;
        }
        final DateTimePoint roundedEnd;
        if (zonedSubDay) {
            final var dayCount = IsoCalendar.differenceISODate(anchor.date(), endDate, Unit.DAY).days();
            final var intermediateDate = IsoCalendar.addDate(anchor.date(), 0, 0, 0, dayCount,
                    RegulateOverflow.CONSTRAIN);
            final var intermediateNanos = toEpochNanos(anchor, intermediateDate, anchor.time());
            final var subDayTotal = endNanos.subtract(intermediateNanos);
            final var incrementNanos = DurationMath.nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment));
            final var roundedSubDay = DurationMath.roundSignedTotalNanoseconds(subDayTotal, incrementNanos, mode);
            roundedEnd = fromEpochNanos(anchor, intermediateNanos.add(roundedSubDay));
        } else if (!smallestUnit.isLargerThan(Unit.DAY)) {
            final var totalNanos = endNanos.subtract(anchorNanos);
            final var incrementNanos = DurationMath.nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment));
            final var roundedTotal = DurationMath.roundSignedTotalNanoseconds(totalNanos, incrementNanos, mode);
            roundedEnd = fromEpochNanos(anchor, anchorNanos.add(roundedTotal));
        } else {
            final var unrounded = DurationMath.differenceCalendar(anchor.date(), anchor.time(), endDate, endTime,
                    largestUnit);
            final var intermediateDate = applyCoarserThan(anchor.date(), unrounded, smallestUnit);
            final var intermediateAnchor = new Anchor(intermediateDate, anchor.time(), anchor.zone());
            final var sign = endNanos.compareTo(anchorNanos) > 0 ? 1 : -1;
            final var units = roundedSignedUnitCount(intermediateAnchor, endDate, endNanos, smallestUnit, increment,
                    sign, mode);
            final var newDate = addUnits(intermediateDate, smallestUnit, units);
            roundedEnd = new DateTimePoint(newDate, anchor.time());
        }
        final var result = DurationMath.differenceCalendar(anchor.date(), anchor.time(), roundedEnd.date(),
                roundedEnd.time(), largestUnit);
        return refineDaysToWeeks(result, largestUnit, smallestUnit);
    }

    private static DurationFields refineDaysToWeeks(DurationFields fields, Unit largestUnit, Unit smallestUnit) {
        if (smallestUnit != Unit.WEEK || !largestUnit.isLargerThan(Unit.WEEK)) {
            return fields;
        }
        final var totalDays = (long) fields.days();
        final var weeks = totalDays / 7;
        final var days = totalDays % 7;
        return new DurationFields(fields.years(), fields.months(), weeks, days, fields.hours(), fields.minutes(),
                fields.seconds(), fields.milliseconds(), fields.microseconds(), fields.nanoseconds());
    }

    private static Iso8601Fields applyCoarserThan(Iso8601Fields date, DurationFields unrounded, Unit smallestUnit) {
        final var years = Unit.YEAR.ordinal() < smallestUnit.ordinal() ? unrounded.years() : 0;
        final var months = Unit.MONTH.ordinal() < smallestUnit.ordinal() ? unrounded.months() : 0;
        final var weeks = Unit.WEEK.ordinal() < smallestUnit.ordinal() ? unrounded.weeks() : 0;
        return IsoCalendar.addDate(date, years, months, weeks, 0, RegulateOverflow.CONSTRAIN);
    }

    public static double totalInUnit(Anchor anchor, Iso8601Fields endDate, IsoTimeFields endTime, Unit unit) {
        final var anchorNanos = toEpochNanos(anchor, anchor.date(), anchor.time());
        final var endNanos = toEpochNanos(anchor, endDate, endTime);
        if (anchor.isZoned() && unit == Unit.DAY) {
            final var nextDay = IsoCalendar.addDate(anchor.date(), 0, 0, 0, 1, RegulateOverflow.CONSTRAIN);
            toEpochNanos(anchor, nextDay, anchor.time());
        }
        final var totalNanos = endNanos.subtract(anchorNanos);
        if (!unit.isLargerThan(Unit.DAY)) {
            return exactDivide(totalNanos, DurationMath.nanosPerUnit(unit));
        }
        if (totalNanos.signum() == 0) {
            return 0.0;
        }
        final var sign = totalNanos.signum() > 0 ? 1 : -1;
        var group = estimateGroups(anchor.date(), endDate, unit, 1);
        while (withinOrAtBoundary(bracketNanos(anchor, unit, sign, group + 1), endNanos, sign)) {
            group++;
        }
        while (group > 0 && !withinOrAtBoundary(bracketNanos(anchor, unit, sign, group), endNanos, sign)) {
            group--;
        }
        final var lower = bracketNanos(anchor, unit, sign, group);
        final var upper = bracketNanos(anchor, unit, sign, group + 1);
        final var span = upper.subtract(lower);
        final var numerator = BigInteger.valueOf(group).multiply(span).add(endNanos.subtract(lower));
        return sign * exactDivide(numerator, span);
    }

    private static double exactDivide(BigInteger numerator, BigInteger denominator) {
        return new java.math.BigDecimal(numerator)
                .divide(new java.math.BigDecimal(denominator), new java.math.MathContext(50)).doubleValue();
    }

    public static int compareApplied(Anchor anchor, DurationFields one, DurationFields two, RegulateOverflow overflow) {
        final var pointOne = applyDuration(anchor, one, overflow);
        final var pointTwo = applyDuration(anchor, two, overflow);
        final var nanosOne = toEpochNanosUnbounded(anchor, pointOne.date(), pointOne.time());
        final var nanosTwo = toEpochNanosUnbounded(anchor, pointTwo.date(), pointTwo.time());
        return nanosOne.compareTo(nanosTwo);
    }

    private static long roundedSignedUnitCount(Anchor anchor, Iso8601Fields endDate, BigInteger endNanos, Unit unit,
            long increment, int sign, RoundingMode mode) {
        var group = estimateGroups(anchor.date(), endDate, unit, increment);
        while (withinOrAtBoundary(bracketNanos(anchor, unit, sign, (group + 1) * increment), endNanos, sign)) {
            group++;
        }
        while (group > 0 && !withinOrAtBoundary(bracketNanos(anchor, unit, sign, group * increment), endNanos, sign)) {
            group--;
        }
        final var lower = bracketNanos(anchor, unit, sign, group * increment);
        final var upper = bracketNanos(anchor, unit, sign, (group + 1) * increment);
        final var remainderAbs = endNanos.subtract(lower).abs();
        final var spanAbs = upper.subtract(lower).abs();
        final var signedQuotient = BigInteger.valueOf(group).multiply(BigInteger.valueOf(sign));
        final var roundedSignedGroup = DurationMath.roundedQuotient(signedQuotient, remainderAbs, spanAbs, sign, mode);
        return roundedSignedGroup.longValueExact() * increment;
    }

    private static boolean withinOrAtBoundary(BigInteger candidateNanos, BigInteger endNanos, int sign) {
        return sign > 0 ? candidateNanos.compareTo(endNanos) <= 0 : candidateNanos.compareTo(endNanos) >= 0;
    }

    private static BigInteger bracketNanos(Anchor anchor, Unit unit, int sign, long units) {
        final var date = addUnits(anchor.date(), unit, (long) sign * units);
        return toEpochNanos(anchor, date, anchor.time());
    }

    private static Iso8601Fields addUnits(Iso8601Fields date, Unit unit, long units) {
        return switch (unit) {
            case YEAR -> IsoCalendar.addDate(date, units, 0, 0, 0, RegulateOverflow.CONSTRAIN);
            case MONTH -> IsoCalendar.addDate(date, 0, units, 0, 0, RegulateOverflow.CONSTRAIN);
            case WEEK -> IsoCalendar.addDate(date, 0, 0, units, 0, RegulateOverflow.CONSTRAIN);
            default -> throw new IllegalArgumentException("addUnits only supports year/month/week, got " + unit);
        };
    }

    private static long estimateGroups(Iso8601Fields anchorDate, Iso8601Fields endDate, Unit unit, long increment) {
        final var diff = IsoCalendar.differenceISODate(anchorDate, endDate, unit);
        final long wholeUnits = switch (unit) {
            case YEAR -> (long) diff.years();
            case MONTH -> (long) diff.months();
            case WEEK -> (long) diff.weeks();
            default -> 0;
        };
        return Math.floorDiv(Math.abs(wholeUnits), Math.max(increment, 1));
    }
}
