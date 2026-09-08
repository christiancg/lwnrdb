package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

/**
 * The field-bag surfaces of the Temporal types: `with`, `from` on an object, and the conversions between the
 * types. A field bag has to be checked rather than defaulted - an empty one, an unknown month code, or two
 * fields disagreeing about the same month are all refusals, not a best guess.
 */
public class TemporalFieldSurfaceTest {
    private static final String ZONED = "Temporal.ZonedDateTime.from('2026-01-02T03:04:05.123456789+00:00[UTC]')";

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String attempt(String expression) {
        return str("(() => { try { return String(" + expression + "); } catch (e) { return e.constructor.name; } })()");
    }

    @Test
    public void test_zoned_with_replaces_single_fields() {
        assertEquals("2027-01-02T03:04:05.123456789+00:00[UTC]", str(ZONED + ".with({ year: 2027 }).toString()"));
        assertEquals("2026-03-02T03:04:05.123456789+00:00[UTC]", str(ZONED + ".with({ monthCode: 'M03' }).toString()"));
    }

    @Test
    public void test_zoned_with_clears_the_whole_time() {
        assertEquals("2026-01-02T00:00:00+00:00[UTC]", str(ZONED
                + ".with({ hour: 0, minute: 0, second: 0, millisecond: 0, microsecond: 0, nanosecond: 0 }).toString()"));
    }

    @Test
    public void test_zoned_with_refuses_an_empty_bag() {
        assertEquals("TypeError", attempt(ZONED + ".with({})"));
    }

    // month and monthCode both name the month, so they may not disagree
    @Test
    public void test_zoned_with_refuses_a_contradictory_month() {
        assertEquals("RangeError", attempt(ZONED + ".with({ month: 3, monthCode: 'M04' })"));
    }

    @Test
    public void test_zoned_with_refuses_a_malformed_month_code() {
        assertEquals("RangeError", attempt(ZONED + ".with({ monthCode: 'Q03' })"));
    }

    // The zone is not a field: changing it would move the instant rather than the wall clock
    @Test
    public void test_zoned_with_refuses_the_time_zone() {
        assertEquals("TypeError", attempt(ZONED + ".with({ timeZone: 'UTC' })"));
    }

    @Test
    public void test_zoned_start_of_day_and_conversions() {
        assertEquals("2026-01-02T00:00:00+00:00[UTC]", str(ZONED + ".startOfDay().toString()"));
        assertEquals("2026-01-02|03:04:05.123456789|2026-01-02T03:04:05.123456789|2026-01|01-02", str("""
                const zoned = %s;
                [
                    zoned.toPlainDate(), zoned.toPlainTime(), zoned.toPlainDateTime(),
                    zoned.toPlainYearMonth(), zoned.toPlainMonthDay()
                ].join('|')
                """.formatted(ZONED)));
    }

    @Test
    public void test_zoned_rounding_accepts_a_day_and_a_divisor_increment() {
        assertEquals("2026-01-02T00:00:00+00:00[UTC]", str(ZONED + ".round('day').toString()"));
        assertEquals("2026-01-02T06:00:00+00:00[UTC]",
                str(ZONED + ".round({ smallestUnit: 'hour', roundingIncrement: 6 }).toString()"));
    }

    // A day increment above one has nothing to divide, so it is refused rather than ignored
    @Test
    public void test_zoned_rounding_refuses_a_day_increment_above_one() {
        assertEquals("RangeError", attempt(ZONED + ".round({ smallestUnit: 'day', roundingIncrement: 2 })"));
    }

    @Test
    public void test_zoned_from_an_object_requires_a_time_zone() {
        assertEquals("TypeError", attempt("Temporal.ZonedDateTime.from({ year: 2026, month: 1, day: 2 })"));
        assertEquals("2026-01-02T00:00:00+00:00[UTC]",
                str("Temporal.ZonedDateTime.from({ year: 2026, month: 1, day: 2, timeZone: 'UTC' }).toString()"));
    }

    @Test
    public void test_year_month_with_and_arithmetic() {
        assertEquals("2027-03|2026-05|2027-02|2025-03", str("""
                const ym = Temporal.PlainYearMonth.from('2026-03');
                [ym.with({ year: 2027 }), ym.with({ monthCode: 'M05' }), ym.add({ months: 11 }),
                    ym.subtract({ years: 1 })].join('|')
                """));
    }

    @Test
    public void test_year_month_differences_round_and_pick_their_largest_unit() {
        assertEquals("P1Y2M|P14M|P2Y", str("""
                const ym = Temporal.PlainYearMonth.from('2026-03');
                [
                    ym.until(Temporal.PlainYearMonth.from('2027-05')),
                    ym.since(Temporal.PlainYearMonth.from('2025-01'), { largestUnit: 'month' }),
                    ym.until(Temporal.PlainYearMonth.from('2027-05'), { smallestUnit: 'year', roundingMode: 'ceil' })
                ].join('|')
                """));
    }

    @Test
    public void test_year_month_reports_its_calendar_lengths() {
        assertEquals("31/365/false/12", str("""
                const ym = Temporal.PlainYearMonth.from('2026-03');
                [ym.daysInMonth, ym.daysInYear, ym.inLeapYear, ym.monthsInYear].join('/')
                """));
    }

    @Test
    public void test_year_month_needs_a_day_to_become_a_date() {
        assertEquals("2026-03-15", str("Temporal.PlainYearMonth.from('2026-03').toPlainDate({ day: 15 }).toString()"));
        assertEquals("TypeError", attempt("Temporal.PlainYearMonth.from('2026-03').toPlainDate({})"));
    }

    @Test
    public void test_year_month_refuses_an_empty_or_impossible_bag() {
        assertEquals("TypeError", attempt("Temporal.PlainYearMonth.from('2026-03').with({})"));
        assertEquals("RangeError", attempt("Temporal.PlainYearMonth.from('2026-03').with({ monthCode: 'M13' })"));
        assertEquals("TypeError", attempt("Temporal.PlainYearMonth.from({ year: 2026 })"));
    }

    // A time bag needs at least one field, but the rest default to zero
    @Test
    public void test_plain_time_from_an_object_needs_one_field() {
        assertEquals("03:04:00", str("Temporal.PlainTime.from({ hour: 3, minute: 4 }).toString()"));
        assertEquals("TypeError", attempt("Temporal.PlainTime.from({})"));
    }

    @Test
    public void test_plain_time_with_rounds_and_differences() {
        assertEquals("23:04:05|03:04:00|PT115M55S", str("""
                const time = Temporal.PlainTime.from('03:04:05');
                [
                    time.with({ hour: 23 }),
                    time.round({ smallestUnit: 'minute', roundingMode: 'halfExpand' }),
                    time.until(Temporal.PlainTime.from('05:00'), { largestUnit: 'minute' })
                ].join('|')
                """));
    }

    @Test
    public void test_plain_time_refuses_a_unit_it_has_no_room_for() {
        assertEquals("RangeError", attempt("Temporal.PlainTime.from('03:04:05').round({ smallestUnit: 'day' })"));
    }
}
