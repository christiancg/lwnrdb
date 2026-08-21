package org.techhouse.simplejs.internal.temporal;

import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.techhouse.simplejs.exceptions.RangeErrorException;

/**
 * Rounding/totaling/comparing a {@link DurationFields} relative to a real calendar anchor date(+time)
 * - the calendar-aware balancing that {@link DurationMath} deliberately leaves out (a "month" has no
 * fixed length without an anchor to measure it from). Shared by {@code Temporal.Duration.prototype.
 * round}/{@code total}, {@code Temporal.Duration.compare} (an explicit {@code relativeTo} option) and
 * {@code Temporal.PlainDateTime}/{@code Temporal.ZonedDateTime}'s {@code since}/{@code until} (an
 * implicit relativeTo - the receiver itself), so the bracket-search algorithm below lives in exactly
 * one place.
 *
 * <p><b>The core idea.</b> A "day" (and everything smaller) is fixed-length, so
 * {@link DurationMath}'s existing nanosecond-total rounding already handles {@code smallestUnit <=
 * day} exactly - this class only has to turn the rounded nanosecond total back into a real calendar
 * point. A calendar unit (week/month/year) is NOT fixed-length in the abstract, but once two adjacent
 * candidate boundaries (e.g. "anchor + 3 months" and "anchor + 4 months") are resolved to real
 * calendar dates, the exact nanosecond span between them - and the exact nanosecond position of the
 * unrounded endpoint within that span - are both exactly computable. Feeding that span and position
 * into {@link DurationMath#roundedQuotient} (the same half/away-from-zero/toward-zero decision table
 * used for a fixed-length increment) is the generalization that makes calendar-unit rounding "just
 * work": the increment's length varies bracket to bracket, but the rounding decision within one
 * bracket does not care.
 */
public final class RelativeDurationMath {
    private static final BigInteger NANOS_PER_DAY = BigInteger.valueOf(86_400_000_000_000L);
    private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000L);

    /**
     * A relativeTo anchor: an ISO local date+time, plus (when the anchor is a {@code
     * Temporal.ZonedDateTime}) the real {@link ZoneId} that turns a candidate local date+time into an
     * exact instant. {@code zone == null} means a plain (calendar-only, no DST) anchor - {@code
     * Temporal.PlainDate}/{@code PlainDateTime}, an ISO string without a bracketed time zone, or a
     * plain fields-like object.
     */
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

    // ---- exact instant resolution ----

    // Temporal's representable instant range is exactly +-8.64e21 nanoseconds from the epoch (+-1e8
    // days, i.e. -271821-04-19T00:00Z .. +275760-09-13T00:00Z) - far narrower than java.time's
    // LocalDate/Instant (which tolerate up to ~999,999,999 years), so a huge-but-java.time-valid date
    // (e.g. applying a Number.MAX_SAFE_INTEGER-seconds Duration to a relativeTo date, or 1ns past the
    // exact boundary date) would otherwise silently succeed here instead of throwing like every other
    // Temporal construction path.
    private static final BigInteger MAX_INSTANT_NANOS = new BigInteger("8640000000000000000000");

    // A plain (non-zoned) anchor's ISO Date-Time Limits are date-only - any time-of-day on either
    // boundary CALENDAR DAY is representable (e.g. "+275760-09-13T23:00" is valid), unlike a zoned
    // anchor, which must resolve to a real instant within +-8.64e21ns of the epoch (so "T00:00Z" is
    // the latest valid moment on that same boundary day). Using the exact-instant nanos check for a
    // plain anchor too would wrongly reject any time past midnight on the max boundary day.
    private static final Iso8601Fields MIN_PLAIN_DATE = new Iso8601Fields(-271821, 4, 19);
    private static final Iso8601Fields MAX_PLAIN_DATE = new Iso8601Fields(275760, 9, 13);

    public static BigInteger toEpochNanos(Anchor anchor, Iso8601Fields date, IsoTimeFields time) {
        if (!anchor.isZoned()) {
            if (IsoCalendar.compareIsoDate(date, MIN_PLAIN_DATE) < 0
                    || IsoCalendar.compareIsoDate(date, MAX_PLAIN_DATE) > 0) {
                throw new RangeErrorException("date value is outside the representable range: " + date);
            }
            return plainEpochNanos(date, time);
        }
        final var nanos = resolveZonedEpochNanos(date, time, anchor.zone());
        if (nanos.abs().compareTo(MAX_INSTANT_NANOS) > 0) {
            throw new RangeErrorException("date value is outside the representable range: " + date);
        }
        return nanos;
    }

    // Temporal.Duration.compare's relativeTo-anchored path only ever compares two intermediate exact
    // times - it never constructs (or exposes) a real Temporal.Instant/ZonedDateTime from them - so,
    // unlike round()/total() (which materialise a real applied result the caller can observe), it must
    // not throw merely because one side's raw arithmetic steps outside the +-8.64e21ns public range.
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

    // Local-to-instant resolution always uses "compatible" disambiguation, matching the spec's
    // AddZonedDateTime/DifferenceZonedDateTime algorithms (neither takes a disambiguation option) -
    // the same convention TemporalZonedDateTimeBuiltins' own add/subtract already follow. Compatible
    // resolves to the pre-transition offset for BOTH a gap and a fold (see TemporalZonedDateTimeBuiltins
    // .resolveLocal's own gap-case comment for why that is the correct choice, not just a coincidence),
    // so a transition needs no gap/fold branch at all here - only "was there a transition".
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

    // BigInteger#mod is always non-negative for a positive modulus, so subtracting it from the
    // dividend and dividing gives floor division/modulo - the same trick the plain-anchor day-carry
    // arithmetic elsewhere in the Temporal builtins already relies on.
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

    // ---- applying a duration to an anchor (AddDateTime/AddZonedDateTime) ----

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

    // ---- rounded/total/compare over the anchor -> end span ----

    /**
     * The relativeTo-anchored equivalent of {@link DurationMath#roundDuration}: rounds the exact
     * (unrounded) span from {@code anchor} to {@code (endDate, endTime)} at {@code smallestUnit}
     * granularity and re-expresses it no larger than {@code largestUnit}. Used whenever the span
     * involves a calendar unit above "day" - either directly ({@code largestUnit}/{@code
     * smallestUnit} is week/month/year) or because the anchor is real and rounding must be able to
     * carry across a month/year boundary.
     */
    public static DurationFields roundedDifference(Anchor anchor, Iso8601Fields endDate, IsoTimeFields endTime,
            Unit largestUnit, Unit smallestUnit, long increment, RoundingMode mode) {
        final var anchorNanos = toEpochNanos(anchor, anchor.date(), anchor.time());
        final var endNanos = toEpochNanos(anchor, endDate, endTime);
        if (endNanos.compareTo(anchorNanos) == 0) {
            return DurationFields.ZERO;
        }
        final DateTimePoint roundedEnd;
        if (!smallestUnit.isLargerThan(Unit.DAY)) {
            final var totalNanos = endNanos.subtract(anchorNanos);
            final var incrementNanos = DurationMath.nanosPerUnit(smallestUnit).multiply(BigInteger.valueOf(increment));
            final var roundedTotal = DurationMath.roundSignedTotalNanoseconds(totalNanos, incrementNanos, mode);
            roundedEnd = fromEpochNanos(anchor, anchorNanos.add(roundedTotal));
        } else {
            // The bracket search below measures "how many whole smallestUnits" separate the endpoint
            // from an anchor - but that anchor must be advanced past every unit COARSER than
            // smallestUnit first (e.g. largestUnit "year", smallestUnit "month": the years are already
            // exact, so only the month-and-below remainder should be on the table for rounding).
            // Skipping this step would measure "weeks since the receiver" instead of "weeks since the
            // whole-months mark", spuriously rounding up an exact month multiple that merely isn't
            // itself a whole number of weeks from the original anchor.
            final var unrounded = DurationMath.differenceCalendar(anchor.date(), anchor.time(), endDate, endTime,
                    largestUnit);
            final var intermediateDate = applyCoarserThan(anchor.date(), unrounded, smallestUnit);
            final var intermediateAnchor = new Anchor(intermediateDate, anchor.time(), anchor.zone());
            final var sign = endNanos.compareTo(anchorNanos) > 0 ? 1 : -1;
            final var units = roundedSignedUnitCount(intermediateAnchor, endDate, endTime, endNanos, smallestUnit,
                    increment, sign, mode);
            final var newDate = addUnits(intermediateDate, smallestUnit, units, RegulateOverflow.CONSTRAIN);
            roundedEnd = new DateTimePoint(newDate, anchor.time());
        }
        final var result = DurationMath.differenceCalendar(anchor.date(), anchor.time(), roundedEnd.date(),
                roundedEnd.time(), largestUnit);
        return refineDaysToWeeks(result, largestUnit, smallestUnit);
    }

    // DurationMath.differenceCalendar's largestUnit "year"/"month" cases never populate a `weeks`
    // field (java.time.Period has no week concept) - their day remainder needs one more split when
    // smallestUnit is specifically "week", e.g. largestUnit "year", smallestUnit "week" on an exact
    // 27-day remainder must come out as 3 weeks 6 days, not 0 weeks 27 days.
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

    // The portion of `unrounded` coarser than smallestUnit (e.g. smallestUnit "month" keeps only
    // years), applied to `date` - see roundedDifference's calendar-unit branch.
    private static Iso8601Fields applyCoarserThan(Iso8601Fields date, DurationFields unrounded, Unit smallestUnit) {
        final var years = Unit.YEAR.ordinal() < smallestUnit.ordinal() ? unrounded.years() : 0;
        final var months = Unit.MONTH.ordinal() < smallestUnit.ordinal() ? unrounded.months() : 0;
        final var weeks = Unit.WEEK.ordinal() < smallestUnit.ordinal() ? unrounded.weeks() : 0;
        return IsoCalendar.addDate(date, years, months, weeks, 0, RegulateOverflow.CONSTRAIN);
    }

    /**
     * The relativeTo-anchored equivalent of {@code Temporal.Duration.prototype.total}: the exact
     * (possibly fractional) count of {@code unit}s from {@code anchor} to {@code (endDate, endTime)},
     * with no final rounding.
     */
    public static double totalInUnit(Anchor anchor, Iso8601Fields endDate, IsoTimeFields endTime, Unit unit) {
        final var anchorNanos = toEpochNanos(anchor, anchor.date(), anchor.time());
        final var endNanos = toEpochNanos(anchor, endDate, endTime);
        final var totalNanos = endNanos.subtract(anchorNanos);
        if (!unit.isLargerThan(Unit.DAY)) {
            return exactDivide(totalNanos, DurationMath.nanosPerUnit(unit));
        }
        if (totalNanos.signum() == 0) {
            return 0.0;
        }
        final var sign = totalNanos.signum() > 0 ? 1 : -1;
        final var g = estimateGroups(anchor.date(), endDate, unit, 1);
        var group = g;
        while (withinOrAtBoundary(bracketNanos(anchor, unit, sign, group + 1), endNanos, sign)) {
            group++;
        }
        while (group > 0 && !withinOrAtBoundary(bracketNanos(anchor, unit, sign, group), endNanos, sign)) {
            group--;
        }
        final var lower = bracketNanos(anchor, unit, sign, group);
        final var upper = bracketNanos(anchor, unit, sign, group + 1);
        final var span = upper.subtract(lower);
        // group + fraction is computed as a single exact BigDecimal division (rather than dividing the
        // fraction alone and adding it to `group` as doubles) so there is only one double-rounding
        // step - adding an already-rounded fractional double to an integer can round to a different
        // nearest double than rounding the combined exact rational value once.
        final var numerator = BigInteger.valueOf(group).multiply(span).add(endNanos.subtract(lower));
        return sign * exactDivide(numerator, span);
    }

    // A double has ~17 significant decimal digits; computing to 50 SIGNIFICANT digits (MathContext,
    // not a fixed post-point scale - a fixed scale loses precision on a small quotient with several
    // leading zeros) leaves an enormous margin so the later BigDecimal -> double conversion is exact
    // (equivalent to correctly rounding the true mathematical quotient to the nearest double, matching
    // spec's "compute the exact value, then round to a Number" semantics) regardless of this
    // intermediate rounding mode's tie-breaking choice.
    private static double exactDivide(BigInteger numerator, BigInteger denominator) {
        return new java.math.BigDecimal(numerator)
                .divide(new java.math.BigDecimal(denominator), new java.math.MathContext(50)).doubleValue();
    }

    /**
     * Applies each of {@code one}/{@code two} to {@code anchor} and compares the resulting exact
     * instants - {@code Temporal.Duration.compare}'s relativeTo-anchored path.
     */
    public static int compareApplied(Anchor anchor, DurationFields one, DurationFields two, RegulateOverflow overflow) {
        final var pointOne = applyDuration(anchor, one, overflow);
        final var pointTwo = applyDuration(anchor, two, overflow);
        final var nanosOne = toEpochNanosUnbounded(anchor, pointOne.date(), pointOne.time());
        final var nanosTwo = toEpochNanosUnbounded(anchor, pointTwo.date(), pointTwo.time());
        return nanosOne.compareTo(nanosTwo);
    }

    // ---- calendar-unit bracket search ----

    private static long roundedSignedUnitCount(Anchor anchor, Iso8601Fields endDate, IsoTimeFields endTime,
            BigInteger endNanos, Unit unit, long increment, int sign, RoundingMode mode) {
        final var g = estimateGroups(anchor.date(), endDate, unit, increment);
        var group = g;
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
        // roundedQuotient expects an already-signed quotient (as the fixed-length caller passes from
        // BigInteger#divideAndRemainder on a signed total) so FLOOR/CEIL/EXPAND move it the right way;
        // `group` here is only ever a non-negative magnitude, so it must be signed before the call.
        final var signedQuotient = BigInteger.valueOf(group).multiply(BigInteger.valueOf(sign));
        final var roundedSignedGroup = DurationMath.roundedQuotient(signedQuotient, remainderAbs, spanAbs, sign, mode);
        return roundedSignedGroup.longValueExact() * increment;
    }

    private static boolean withinOrAtBoundary(BigInteger candidateNanos, BigInteger endNanos, int sign) {
        return sign > 0 ? candidateNanos.compareTo(endNanos) <= 0 : candidateNanos.compareTo(endNanos) >= 0;
    }

    // The exact epoch nanoseconds of "anchor + sign*units of `unit`" (time-of-day fixed at the
    // anchor's own time - only the calendar date advances while searching for a bracket).
    private static BigInteger bracketNanos(Anchor anchor, Unit unit, int sign, long units) {
        final var date = addUnits(anchor.date(), unit, (long) sign * units, RegulateOverflow.CONSTRAIN);
        return toEpochNanos(anchor, date, anchor.time());
    }

    private static Iso8601Fields addUnits(Iso8601Fields date, Unit unit, long units, RegulateOverflow overflow) {
        return switch (unit) {
            case YEAR -> IsoCalendar.addDate(date, units, 0, 0, 0, overflow);
            case MONTH -> IsoCalendar.addDate(date, 0, units, 0, 0, overflow);
            case WEEK -> IsoCalendar.addDate(date, 0, 0, units, 0, overflow);
            default -> throw new IllegalArgumentException("addUnits only supports year/month/week, got " + unit);
        };
    }

    // A cheap starting estimate (whole date-only units, via the same Period-based breakdown
    // IsoCalendar.differenceISODate already computes) for the bracket search below to correct by at
    // most a step or two - the estimate ignores time-of-day, so the anchor/end time difference can
    // put it off by one, which the while-loops in the callers above fix up exactly.
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
