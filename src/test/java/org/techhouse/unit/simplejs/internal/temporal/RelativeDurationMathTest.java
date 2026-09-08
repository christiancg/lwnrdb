package org.techhouse.unit.simplejs.internal.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.Iso8601Fields;
import org.techhouse.simplejs.internal.temporal.IsoTimeFields;
import org.techhouse.simplejs.internal.temporal.RegulateOverflow;
import org.techhouse.simplejs.internal.temporal.RelativeDurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.Unit;

// Direct API coverage for RelativeDurationMath's exact-instant resolution and calendar-unit bracket
// search - the paths reachable in principle from script (via Temporal.Duration.round/total/compare or
// PlainDateTime/ZonedDateTime.until/since) but only exercised there through the test262 conformance
// harness (a separate worker JVM, not measured by JaCoCo), plus a few defensive branches (malformed
// Iso8601Fields, BigInteger overflow) that valid script input can never actually construct.
public class RelativeDurationMathTest {
    private static final IsoTimeFields MIDNIGHT = new IsoTimeFields(0, 0, 0, 0, 0, 0);

    @Test
    public void test_to_epoch_nanos_plain_anchor_round_trip() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        final var nanos = RelativeDurationMath.toEpochNanos(anchor, new Iso8601Fields(2020, 1, 2), MIDNIGHT);
        final var point = RelativeDurationMath.fromEpochNanos(anchor, nanos);
        assertEquals(2020, point.date().year());
        assertEquals(1, point.date().month());
        assertEquals(2, point.date().day());
    }

    @Test
    public void test_to_epoch_nanos_rejects_date_outside_representable_range() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        assertThrows(RangeErrorException.class,
                () -> RelativeDurationMath.toEpochNanos(anchor, new Iso8601Fields(300_000, 1, 1), MIDNIGHT));
        assertThrows(RangeErrorException.class,
                () -> RelativeDurationMath.toEpochNanos(anchor, new Iso8601Fields(-300_000, 1, 1), MIDNIGHT));
    }

    @Test
    public void test_to_epoch_nanos_plain_rejects_invalid_calendar_date() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        // month 13 is not a valid java.time.LocalDate, exercising plainEpochNanos' DateTimeException path
        assertThrows(RangeErrorException.class,
                () -> RelativeDurationMath.toEpochNanos(anchor, new Iso8601Fields(2020, 13, 1), MIDNIGHT));
    }

    @Test
    public void test_from_epoch_nanos_plain_rejects_arithmetic_overflow() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        assertThrows(RangeErrorException.class,
                () -> RelativeDurationMath.fromEpochNanos(anchor, BigInteger.TEN.pow(30)));
    }

    @Test
    public void test_from_epoch_nanos_zoned_rejects_arithmetic_overflow() {
        final var anchor = RelativeDurationMath.Anchor.zoned(new Iso8601Fields(2020, 1, 1), MIDNIGHT, ZoneId.of("UTC"));
        assertThrows(RangeErrorException.class,
                () -> RelativeDurationMath.fromEpochNanos(anchor, BigInteger.TEN.pow(30)));
    }

    @Test
    public void test_to_epoch_nanos_zoned_rejects_invalid_time_of_day() {
        final var anchor = RelativeDurationMath.Anchor.zoned(new Iso8601Fields(2020, 1, 1), MIDNIGHT, ZoneId.of("UTC"));
        // hour 99 is not a valid java.time.LocalDateTime, exercising toLocalDateTime's catch branch
        assertThrows(RangeErrorException.class, () -> RelativeDurationMath.toEpochNanos(anchor,
                new Iso8601Fields(2020, 1, 1), new IsoTimeFields(99, 0, 0, 0, 0, 0)));
    }

    // A zoned anchor's exact-instant resolution walks a real DST gap (spring-forward, a nonexistent
    // local time) and fold (fall-back, an ambiguous local time) - both branches of getTransition, not
    // just the "no transition" (fixed-offset/UTC) case every other test in this file exercises.
    @Test
    public void test_to_epoch_nanos_zoned_resolves_across_dst_gap_and_fold() {
        final var newYork = ZoneId.of("America/New_York");
        final var gapAnchor = RelativeDurationMath.Anchor.zoned(new Iso8601Fields(2020, 3, 8), MIDNIGHT, newYork);
        // 2020-03-08T02:30 does not exist in America/New_York (clocks spring forward 02:00 -> 03:00)
        final var gapNanos = RelativeDurationMath.toEpochNanos(gapAnchor, new Iso8601Fields(2020, 3, 8),
                new IsoTimeFields(2, 30, 0, 0, 0, 0));
        final var foldAnchor = RelativeDurationMath.Anchor.zoned(new Iso8601Fields(2020, 11, 1), MIDNIGHT, newYork);
        // 2020-11-01T01:30 occurs twice in America/New_York (clocks fall back 02:00 -> 01:00)
        final var foldNanos = RelativeDurationMath.toEpochNanos(foldAnchor, new Iso8601Fields(2020, 11, 1),
                new IsoTimeFields(1, 30, 0, 0, 0, 0));
        // Both must resolve to *some* real instant without throwing; exact instant is a resolution
        // policy detail already covered by TemporalZonedDateTimeBuiltins' own disambiguation tests.
        assertEquals(true, gapNanos.signum() > 0);
        assertEquals(true, foldNanos.signum() > 0);
    }

    @Test
    public void test_apply_duration_zoned_with_and_without_date_part() {
        final var anchor = RelativeDurationMath.Anchor.zoned(new Iso8601Fields(2020, 1, 1), MIDNIGHT, ZoneId.of("UTC"));
        // No date-part field set (years/months/weeks/days all zero) - dateAfterCalendar stays the
        // anchor's own date unchanged.
        final var timeOnly = RelativeDurationMath.applyDuration(anchor,
                new DurationFields(0, 0, 0, 0, 25, 0, 0, 0, 0, 0), RegulateOverflow.CONSTRAIN);
        assertEquals(2020, timeOnly.date().year());
        assertEquals(2, timeOnly.date().day());
        assertEquals(1, timeOnly.time().hour());

        // A date-part field forces the calendar-add branch before resolving the intermediate instant.
        final var withDate = RelativeDurationMath.applyDuration(anchor,
                new DurationFields(1, 0, 0, 0, 0, 0, 0, 0, 0, 0), RegulateOverflow.CONSTRAIN);
        assertEquals(2021, withDate.date().year());
    }

    @Test
    public void test_rounded_difference_returns_zero_for_identical_endpoint() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        final var result = RelativeDurationMath.roundedDifference(anchor, anchor.date(), anchor.time(), Unit.YEAR,
                Unit.MONTH, 1, RoundingMode.TRUNC);
        assertEquals(DurationFields.ZERO, result);
    }

    // largestUnit above "day" combined with a smallestUnit at or below "day" (e.g. "years"/"hours")
    // takes the exact-nanosecond rounding path, not the calendar-unit bracket search.
    @Test
    public void test_rounded_difference_calendar_largest_with_time_smallest_unit() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        final var end = new Iso8601Fields(2021, 6, 15);
        final var endTime = new IsoTimeFields(13, 0, 0, 0, 0, 0);
        final var result = RelativeDurationMath.roundedDifference(anchor, end, endTime, Unit.YEAR, Unit.HOUR, 1,
                RoundingMode.TRUNC);
        assertEquals(1, result.years());
    }

    @Test
    public void test_total_in_unit_zero_for_identical_endpoint() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        assertEquals(0.0, RelativeDurationMath.totalInUnit(anchor, anchor.date(), anchor.time(), Unit.YEAR));
    }

    @Test
    public void test_total_in_unit_calendar_unit_fractional() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        // Half a (non-leap) year later relative to the following year's bracket
        final var total = RelativeDurationMath.totalInUnit(anchor, new Iso8601Fields(2021, 7, 2), MIDNIGHT, Unit.YEAR);
        assertEquals(true, total > 1.0 && total < 2.0);
    }

    @Test
    public void test_total_in_unit_negative_direction() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2021, 1, 1), MIDNIGHT);
        final var total = RelativeDurationMath.totalInUnit(anchor, new Iso8601Fields(2019, 7, 2), MIDNIGHT, Unit.YEAR);
        assertEquals(true, total < 0.0);
    }

    @Test
    public void test_compare_applied_orders_by_exact_instant() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2016, 1, 1), MIDNIGHT);
        final var oneYear = new DurationFields(1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        final var days365 = new DurationFields(0, 0, 0, 365, 0, 0, 0, 0, 0, 0);
        // 2016 is a leap year: 1 year > 365 days from this anchor
        assertEquals(1, Integer
                .signum(RelativeDurationMath.compareApplied(anchor, oneYear, days365, RegulateOverflow.CONSTRAIN)));
        assertEquals(-1, Integer
                .signum(RelativeDurationMath.compareApplied(anchor, days365, oneYear, RegulateOverflow.CONSTRAIN)));
        assertEquals(0, RelativeDurationMath.compareApplied(anchor, oneYear, oneYear, RegulateOverflow.CONSTRAIN));
    }

    // Rounding with an increment > 1 at a calendar unit exercises the bracket-extension loop (not just
    // the single-unit-step default), in both the positive and negative direction.
    @Test
    public void test_rounded_difference_calendar_unit_increment_extends_bracket() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        final var forward = RelativeDurationMath.roundedDifference(anchor, new Iso8601Fields(2024, 3, 1), MIDNIGHT,
                Unit.YEAR, Unit.YEAR, 2, RoundingMode.EXPAND);
        assertEquals(6.0, forward.years());
        final var backward = RelativeDurationMath.roundedDifference(anchor, new Iso8601Fields(2015, 10, 1), MIDNIGHT,
                Unit.YEAR, Unit.YEAR, 2, RoundingMode.EXPAND);
        assertEquals(-6.0, backward.years());
    }

    // Rounding at "weeks" smallestUnit while largestUnit stays "weeks" (i.e. equal, not coarser) is
    // the no-op branch of refineDaysToWeeks - the days field is already week-native.
    // A weeks-only duration (no years/months/days) still takes applyDuration's zoned calendar-add
    // branch - covers the `duration.weeks() != 0` arm of the hasDatePart disjunction on its own.
    @Test
    public void test_apply_duration_zoned_weeks_only() {
        final var anchor = RelativeDurationMath.Anchor.zoned(new Iso8601Fields(2020, 1, 1), MIDNIGHT, ZoneId.of("UTC"));
        final var result = RelativeDurationMath.applyDuration(anchor, new DurationFields(0, 0, 1, 0, 0, 0, 0, 0, 0, 0),
                RegulateOverflow.CONSTRAIN);
        assertEquals(8, result.date().day());
    }

    // An anchor time-of-day later than the endpoint's forces the calendar-unit bracket search to
    // shrink its date-only estimate by a step (the exact instant - which includes time-of-day - has
    // not actually reached a full year yet, even though the calendar dates alone are a year apart).
    @Test
    public void test_rounded_difference_bracket_search_shrinks_estimate() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1),
                new IsoTimeFields(23, 0, 0, 0, 0, 0));
        final var end = new Iso8601Fields(2021, 1, 1);
        final var endTime = new IsoTimeFields(1, 0, 0, 0, 0, 0);
        final var result = RelativeDurationMath.roundedDifference(anchor, end, endTime, Unit.YEAR, Unit.YEAR, 1,
                RoundingMode.FLOOR);
        assertEquals(0.0, result.years());
    }

    @Test
    public void test_rounded_difference_weeks_smallest_and_largest_unit_equal() {
        final var anchor = RelativeDurationMath.Anchor.plain(new Iso8601Fields(2020, 1, 1), MIDNIGHT);
        final var result = RelativeDurationMath.roundedDifference(anchor, new Iso8601Fields(2020, 1, 22), MIDNIGHT,
                Unit.WEEK, Unit.WEEK, 1, RoundingMode.TRUNC);
        assertEquals(3.0, result.weeks());
        assertEquals(0.0, result.days());
    }
}
