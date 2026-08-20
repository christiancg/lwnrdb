package org.techhouse.unit.simplejs.internal.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.internal.temporal.DurationFields;
import org.techhouse.simplejs.internal.temporal.DurationMath;
import org.techhouse.simplejs.internal.temporal.RoundingMode;
import org.techhouse.simplejs.internal.temporal.Unit;

public class DurationMathTest {
    @Test
    public void test_sign_all_positive() {
        assertEquals(1, DurationMath.sign(new DurationFields(0, 0, 0, 1, 0, 0, 0, 0, 0, 0)));
    }

    @Test
    public void test_sign_all_negative() {
        assertEquals(-1, DurationMath.sign(new DurationFields(0, 0, 0, -1, -2, 0, 0, 0, 0, 0)));
    }

    @Test
    public void test_sign_all_zero() {
        assertEquals(0, DurationMath.sign(DurationFields.ZERO));
    }

    @Test
    public void test_sign_rejects_mixed_signs() {
        assertThrows(RangeErrorException.class,
                () -> DurationMath.sign(new DurationFields(0, 0, 0, 1, -1, 0, 0, 0, 0, 0)));
    }

    @Test
    public void test_sign_rejects_mixed_signs_reversed_order() {
        assertThrows(RangeErrorException.class,
                () -> DurationMath.sign(new DurationFields(0, 0, 0, -1, 0, 0, 1, 0, 0, 0)));
    }

    @Test
    public void test_balance_duration_carries_nanoseconds_up_through_days() {
        final var fields = new DurationFields(0, 0, 0, 0, 0, 0, 0, 0, 0, 90_000_000_000_000L);
        final var result = DurationMath.balanceDuration(fields, Unit.DAY);
        assertEquals(1, result.days());
        assertEquals(1, result.hours());
        assertEquals(0, result.minutes());
        assertEquals(0, result.seconds());
    }

    @Test
    public void test_balance_duration_negative() {
        final var fields = new DurationFields(0, 0, 0, 0, -25, 0, 0, 0, 0, 0);
        final var result = DurationMath.balanceDuration(fields, Unit.DAY);
        assertEquals(-1, result.days());
        assertEquals(-1, result.hours());
    }

    @Test
    public void test_balance_duration_largest_unit_hour_folds_days_in() {
        final var fields = new DurationFields(0, 0, 0, 2, 3, 0, 0, 0, 0, 0);
        final var result = DurationMath.balanceDuration(fields, Unit.HOUR);
        assertEquals(0, result.days());
        assertEquals(51, result.hours());
    }

    @Test
    public void test_balance_duration_zero_returns_zero() {
        final var result = DurationMath.balanceDuration(DurationFields.ZERO, Unit.DAY);
        assertEquals(DurationFields.ZERO, result);
    }

    @Test
    public void test_balance_duration_rejects_year_month_week_largest_unit() {
        final var fields = new DurationFields(0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
        assertThrows(UnsupportedOperationException.class, () -> DurationMath.balanceDuration(fields, Unit.MONTH));
    }

    @Test
    public void test_balance_duration_rejects_nonzero_calendar_fields() {
        final var fields = new DurationFields(1, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertThrows(UnsupportedOperationException.class, () -> DurationMath.balanceDuration(fields, Unit.DAY));
    }

    @Test
    public void test_round_duration_trunc() {
        final var fields = new DurationFields(0, 0, 0, 0, 0, 0, 1, 500, 0, 0);
        final var result = DurationMath.roundDuration(fields, Unit.SECOND, 1, RoundingMode.TRUNC, Unit.SECOND);
        assertEquals(1, result.seconds());
        assertEquals(0, result.milliseconds());
    }

    @Test
    public void test_round_duration_ceil() {
        final var fields = new DurationFields(0, 0, 0, 0, 0, 0, 1, 500, 0, 0);
        final var result = DurationMath.roundDuration(fields, Unit.SECOND, 1, RoundingMode.CEIL, Unit.SECOND);
        assertEquals(2, result.seconds());
    }

    @Test
    public void test_round_duration_floor_negative() {
        final var fields = new DurationFields(0, 0, 0, 0, 0, 0, -1, -500, 0, 0);
        final var result = DurationMath.roundDuration(fields, Unit.SECOND, 1, RoundingMode.FLOOR, Unit.SECOND);
        assertEquals(-2, result.seconds());
    }

    @Test
    public void test_round_duration_half_expand_exact_half_rounds_away_from_zero() {
        final var positive = new DurationFields(0, 0, 0, 0, 0, 0, 0, 500, 0, 0);
        final var positiveResult = DurationMath.roundDuration(positive, Unit.SECOND, 1, RoundingMode.HALF_EXPAND,
                Unit.SECOND);
        assertEquals(1, positiveResult.seconds());

        final var negative = new DurationFields(0, 0, 0, 0, 0, 0, 0, -500, 0, 0);
        final var negativeResult = DurationMath.roundDuration(negative, Unit.SECOND, 1, RoundingMode.HALF_EXPAND,
                Unit.SECOND);
        assertEquals(-1, negativeResult.seconds());
    }

    @Test
    public void test_round_duration_half_trunc_exact_half_stays() {
        final var fields = new DurationFields(0, 0, 0, 0, 0, 0, 0, 500, 0, 0);
        final var result = DurationMath.roundDuration(fields, Unit.SECOND, 1, RoundingMode.HALF_TRUNC, Unit.SECOND);
        assertEquals(0, result.seconds());
    }

    @Test
    public void test_round_duration_half_even_rounds_to_even_neighbor() {
        final var toEven = new DurationFields(0, 0, 0, 0, 0, 0, 1, 500, 0, 0);
        final var toEvenResult = DurationMath.roundDuration(toEven, Unit.SECOND, 1, RoundingMode.HALF_EVEN,
                Unit.SECOND);
        assertEquals(2, toEvenResult.seconds());

        final var staysEven = new DurationFields(0, 0, 0, 0, 0, 0, 2, 500, 0, 0);
        final var staysEvenResult = DurationMath.roundDuration(staysEven, Unit.SECOND, 1, RoundingMode.HALF_EVEN,
                Unit.SECOND);
        assertEquals(2, staysEvenResult.seconds());
    }

    @Test
    public void test_round_duration_nanosecond_boundary() {
        final var fields = new DurationFields(0, 0, 0, 0, 0, 0, 0, 0, 0, 1);
        final var truncated = DurationMath.roundDuration(fields, Unit.MICROSECOND, 1, RoundingMode.TRUNC,
                Unit.MICROSECOND);
        assertEquals(0, truncated.microseconds());

        final var expanded = DurationMath.roundDuration(fields, Unit.MICROSECOND, 1, RoundingMode.CEIL,
                Unit.MICROSECOND);
        assertEquals(1, expanded.microseconds());
    }

    @Test
    public void test_round_duration_rejects_zero_or_negative_increment() {
        final var fields = new DurationFields(0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
        assertThrows(RangeErrorException.class,
                () -> DurationMath.roundDuration(fields, Unit.SECOND, 0, RoundingMode.TRUNC, Unit.SECOND));
    }

    @Test
    public void test_round_duration_rejects_calendar_units() {
        final var fields = new DurationFields(0, 0, 0, 1, 0, 0, 0, 0, 0, 0);
        assertThrows(UnsupportedOperationException.class,
                () -> DurationMath.roundDuration(fields, Unit.WEEK, 1, RoundingMode.TRUNC, Unit.DAY));
    }

    // balanceFromTotalNanoseconds: the AddDurations shape, starting from an already-signed total
    // rather than a fields record (see Temporal.Duration.prototype.add/subtract)
    @Test
    public void test_balance_from_total_nanoseconds_zero() {
        final var result = DurationMath.balanceFromTotalNanoseconds(java.math.BigInteger.ZERO, Unit.DAY);
        assertEquals(DurationFields.ZERO, result);
    }

    @Test
    public void test_balance_from_total_nanoseconds_positive_and_negative() {
        final var positive = DurationMath.balanceFromTotalNanoseconds(java.math.BigInteger.valueOf(90_000_000_000_000L),
                Unit.DAY);
        assertEquals(1, positive.days());
        assertEquals(1, positive.hours());

        final var negative = DurationMath
                .balanceFromTotalNanoseconds(java.math.BigInteger.valueOf(-90_000_000_000_000L), Unit.DAY);
        assertEquals(-1, negative.days());
        assertEquals(-1, negative.hours());
    }

    @Test
    public void test_balance_from_total_nanoseconds_rejects_calendar_largest_unit() {
        assertThrows(UnsupportedOperationException.class,
                () -> DurationMath.balanceFromTotalNanoseconds(java.math.BigInteger.ONE, Unit.MONTH));
    }
}
