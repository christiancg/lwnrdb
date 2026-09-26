package org.techhouse.unit.ops.schedule;

import static org.junit.jupiter.api.Assertions.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;
import org.techhouse.ex.InvalidCronException;
import org.techhouse.ops.schedule.CronExpression;

public class CronExpressionTest {
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static ZonedDateTime utc(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, UTC);
    }

    private static ZonedDateTime next(String expression, ZonedDateTime from) {
        return CronExpression.parse(expression).nextAfter(from);
    }

    @Test
    public void test_parses_every_field_form() {
        assertNotNull(CronExpression.parse("* * * * *"));
        assertNotNull(CronExpression.parse("5 * * * *"));
        assertNotNull(CronExpression.parse("1-5 * * * *"));
        assertNotNull(CronExpression.parse("*/15 * * * *"));
        assertNotNull(CronExpression.parse("1-30/5 * * * *"));
        assertNotNull(CronExpression.parse("1,3,5 * * * *"));
        assertNotNull(CronExpression.parse("0 0 1 JAN *"));
        assertNotNull(CronExpression.parse("0 0 * * MON"));
    }

    @Test
    public void test_step_on_a_bare_value_runs_to_the_end_of_the_range() {
        final var from = utc(2026, 1, 1, 0, 0);
        assertEquals(utc(2026, 1, 1, 0, 5), next("5/15 * * * *", from));
        assertEquals(utc(2026, 1, 1, 0, 20), next("5/15 * * * *", utc(2026, 1, 1, 0, 5)));
    }

    @Test
    public void test_next_after_advances_to_the_next_minute() {
        assertEquals(utc(2026, 5, 4, 10, 1), next("* * * * *", utc(2026, 5, 4, 10, 0)));
    }

    @Test
    public void test_next_after_advances_across_an_hour() {
        assertEquals(utc(2026, 5, 4, 11, 0), next("0 * * * *", utc(2026, 5, 4, 10, 30)));
    }

    @Test
    public void test_next_after_advances_across_a_day() {
        assertEquals(utc(2026, 5, 5, 3, 0), next("0 3 * * *", utc(2026, 5, 4, 10, 30)));
    }

    @Test
    public void test_next_after_advances_across_a_month() {
        assertEquals(utc(2026, 6, 1, 0, 0), next("0 0 1 * *", utc(2026, 5, 4, 10, 30)));
    }

    @Test
    public void test_next_after_advances_across_a_year() {
        assertEquals(utc(2027, 1, 1, 0, 0), next("0 0 1 JAN *", utc(2026, 5, 4, 10, 30)));
    }

    @Test
    public void test_day_of_month_and_day_of_week_are_ored_when_both_restricted() {
        // 2026-05-04 is a Monday; the 15th is a Friday.
        final var expression = CronExpression.parse("0 0 15 5 MON");
        assertEquals(utc(2026, 5, 4, 0, 0), expression.nextAfter(utc(2026, 5, 3, 12, 0)));
        assertEquals(utc(2026, 5, 15, 0, 0), expression.nextAfter(utc(2026, 5, 12, 0, 0)));
    }

    @Test
    public void test_day_of_month_and_day_of_week_are_anded_when_only_one_is_restricted() {
        assertEquals(utc(2026, 5, 15, 0, 0), next("0 0 15 5 *", utc(2026, 5, 1, 0, 0)));
    }

    @Test
    public void test_sunday_may_be_written_as_seven() {
        assertEquals(next("0 0 * * 0", utc(2026, 5, 4, 0, 0)), next("0 0 * * 7", utc(2026, 5, 4, 0, 0)));
    }

    @Test
    public void test_leap_day_is_handled() {
        assertEquals(utc(2028, 2, 29, 0, 0), next("0 0 29 2 *", utc(2026, 3, 1, 0, 0)));
    }

    @Test
    public void test_unsatisfiable_expression_returns_null() {
        assertNull(next("0 0 30 2 *", utc(2026, 1, 1, 0, 0)));
    }

    @Test
    public void test_rejects_malformed_expressions() {
        assertThrows(InvalidCronException.class, () -> CronExpression.parse(null));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("   "));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("* * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("* * * * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("60 * * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("* 24 * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("*/0 * * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("*/x * * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("0 0 * NOPE *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("0 0 * * NOPE"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("5-1 * * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("1,,2 * * * *"));
        assertThrows(InvalidCronException.class, () -> CronExpression.parse("1- * * * *"));
    }

    @Test
    public void test_honours_the_supplied_zone() {
        final var expression = CronExpression.parse("0 3 * * *");
        final var utcNext = expression.nextAfter(utc(2026, 5, 4, 0, 0));
        final var tokyo = ZoneId.of("Asia/Tokyo");
        final var tokyoNext = expression.nextAfter(ZonedDateTime.of(2026, 5, 4, 0, 0, 0, 0, tokyo));
        assert utcNext != null;
        assert tokyoNext != null;
        assertNotEquals(utcNext.toInstant(), tokyoNext.toInstant());
        assertEquals(3, tokyoNext.getHour());
    }

    // The property the at-most-once guarantee rests on: a new owner computing the next occurrence at the
    // exact firing minute gets the following one, never the instant that may already have run.
    @Test
    public void test_next_after_is_strictly_after_the_given_instant() {
        assertEquals(utc(2026, 5, 5, 3, 0), next("0 3 * * *", utc(2026, 5, 4, 3, 0)));
    }

    @Test
    public void test_spring_forward_fires_once_and_does_not_hang() {
        final var newYork = ZoneId.of("America/New_York");
        final var expression = CronExpression.parse("0 2 * * *");
        final var from = ZonedDateTime.of(2026, 3, 8, 0, 0, 0, 0, newYork);
        final var first = expression.nextAfter(from);
        assert first != null;
        assertEquals(2026, first.getYear());
        assertEquals(3, first.getMonthValue());
        assertEquals(8, first.getDayOfMonth());
        assertEquals(3, first.getHour());
        final var second = expression.nextAfter(first);
        assert second != null;
        assertEquals(9, second.getDayOfMonth());
        assertEquals(2, second.getHour());
    }

    @Test
    public void test_fall_back_does_not_fire_twice() {
        final var newYork = ZoneId.of("America/New_York");
        final var expression = CronExpression.parse("0 1 * * *");
        final var from = ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, newYork);
        final var first = expression.nextAfter(from);
        assert first != null;
        assertEquals(1, first.getDayOfMonth());
        assertEquals(1, first.getHour());
        final var second = expression.nextAfter(first);
        assert second != null;
        assertEquals(2, second.getDayOfMonth());
        assertEquals(1, second.getHour());
    }

    @Test
    public void test_sub_hourly_fires_through_a_fall_back_overlap() {
        final var newYork = ZoneId.of("America/New_York");
        final var expression = CronExpression.parse("*/5 * * * *");
        final var from = ZonedDateTime.of(2026, 11, 1, 1, 55, 0, 0, newYork).withEarlierOffsetAtOverlap();
        assertFalse(newYork.getRules().getDaylightSavings(from.toInstant()).isZero(),
                "the executor only ever reaches the overlap at the earlier offset, marching forward from 01:55 EDT");

        final var next = expression.nextAfter(from);

        assert next != null;
        assertEquals(1, next.getHour(), "the walk used to leave the repeated hour entirely");
        assertEquals(0, next.getMinute());
        assertTrue(newYork.getRules().getDaylightSavings(next.toInstant()).isZero(),
                "the twelve occurrences of the repeated hour fire at the later offset");
    }

    @Test
    public void test_every_occurrence_of_the_repeated_hour_fires_exactly_once() {
        final var newYork = ZoneId.of("America/New_York");
        final var expression = CronExpression.parse("*/5 * * * *");
        var current = ZonedDateTime.of(2026, 11, 1, 0, 55, 0, 0, newYork);
        final var fired = new java.util.ArrayList<java.time.Instant>();

        for (var i = 0; i < 25; i++) {
            current = expression.nextAfter(current);
            assert current != null;
            fired.add(current.toInstant());
        }

        assertEquals(fired.size(), new java.util.LinkedHashSet<>(fired).size(), "no occurrence may repeat");
        final var overlapStart = ZonedDateTime.of(2026, 11, 1, 1, 0, 0, 0, newYork).withLaterOffsetAtOverlap();
        final var repeatedHourFires = fired.stream().filter(instant -> !instant.isBefore(overlapStart.toInstant())
                && instant.isBefore(overlapStart.toInstant().plusSeconds(3600))).count();
        assertEquals(12, repeatedHourFires, "a five-minute job must not go dark for the repeated hour");
    }

    @Test
    public void test_a_fixed_time_daily_cron_skips_the_repeated_hour() {
        final var newYork = ZoneId.of("America/New_York");
        final var expression = CronExpression.parse("0 1 * * *");
        final var first = expression.nextAfter(ZonedDateTime.of(2026, 11, 1, 0, 0, 0, 0, newYork));
        assert first != null;

        final var second = expression.nextAfter(first);

        assert second != null;
        assertEquals(2, second.getDayOfMonth(),
                "a cron naming an exact hour and minute fires once, so the wildcard re-scan must not apply to it");
    }

    @Test
    public void test_step_in_the_day_of_month_field_still_ands_with_the_day_of_week() {
        var current = utc(2026, 6, 1, 0, 0);
        for (var i = 0; i < 4; i++) {
            current = next("0 0 */1 * MON", current);
            assert current != null;
            assertEquals(java.time.DayOfWeek.MONDAY, current.getDayOfWeek(),
                    "'*/1' spells every day, so it must not turn the day fields into an OR");
        }
    }

    @Test
    public void test_star_and_step_one_agree_in_the_day_field() {
        final var from = utc(2026, 6, 1, 0, 0);
        assertEquals(next("0 0 * * MON", from), next("0 0 */1 * MON", from));
    }

    @Test
    public void test_spring_forward_still_skips_the_missing_hour() {
        final var newYork = ZoneId.of("America/New_York");
        final var expression = CronExpression.parse("*/15 * * * *");
        var current = ZonedDateTime.of(2026, 3, 8, 1, 50, 0, 0, newYork);

        current = expression.nextAfter(current);
        assert current != null;

        assertEquals(3, current.getHour(), "02:00-02:59 does not exist on this day");
        assertEquals(0, current.getMinute());
    }
}
