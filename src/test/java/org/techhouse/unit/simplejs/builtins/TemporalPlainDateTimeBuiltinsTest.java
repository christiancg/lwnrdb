package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalPlainDateTimeBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_constructor_validation() {
        assertEquals("2020,6,15,10,30,0", str("var d = new Temporal.PlainDateTime(2020, 6, 15, 10, 30);"
                + "d.year + ',' + d.month + ',' + d.day + ',' + d.hour + ',' + d.minute + ',' + d.second"));
        assertEquals("0,0,0,0,0,0",
                str("var d = new Temporal.PlainDateTime(2020, 6, 15);"
                        + "d.hour + ',' + d.minute + ',' + d.second + ',' + d.millisecond + ',' + d.microsecond + ','"
                        + "+ d.nanosecond"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 13, 1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15, 24)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainDateTime(2020, 6, 15)"));
    }

    @Test
    public void test_constructor_calendar_validation() {
        assertEquals("iso8601", str("new Temporal.PlainDateTime(2020, 6, 15, 0, 0, 0, 0, 0, 0, 'iso8601').calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15, 0, 0, 0, 0, 0, 0, 'gregory')"));
    }

    @Test
    public void test_type_identity() {
        assertEquals("object", str("typeof new Temporal.PlainDateTime(2020, 6, 15)"));
        assertTrue(bool("new Temporal.PlainDateTime(2020, 6, 15) instanceof Temporal.PlainDateTime"));
        assertEquals("[object Temporal.PlainDateTime]",
                str("Object.prototype.toString.call(new Temporal.PlainDateTime(2020, 6, 15))"));
    }

    @Test
    public void test_field_accessors() {
        assertEquals("2020,6,15,M06", str("var d = new Temporal.PlainDateTime(2020, 6, 15, 1, 2, 3);"
                + "d.year + ',' + d.month + ',' + d.day + ',' + d.monthCode"));
        assertEquals(7, num("new Temporal.PlainDateTime(2020, 6, 15).daysInWeek"));
        assertEquals(12, num("new Temporal.PlainDateTime(2020, 6, 15).monthsInYear"));
        assertEquals(29, num("new Temporal.PlainDateTime(2020, 2, 1).daysInMonth"));
        assertEquals(366, num("new Temporal.PlainDateTime(2020, 1, 1).daysInYear"));
        assertTrue(bool("new Temporal.PlainDateTime(2020, 1, 1).inLeapYear"));
        assertEquals("iso8601", str("new Temporal.PlainDateTime(2020, 1, 1).calendarId"));
    }

    @Test
    public void test_accessor_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(Temporal.PlainDateTime.prototype, 'hour')" + ".get.call({})"));
    }

    @Test
    public void test_method_brand_check() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.prototype.toString.call({})"));
    }

    @Test
    public void test_with() {
        assertEquals("2020,6,20,10", str("var d = new Temporal.PlainDateTime(2020, 1, 15, 10);"
                + "var e = d.with({month: 6, day: 20}); e.year + ',' + e.month + ',' + e.day + ',' + e.hour"));
        assertEquals("11,30", str("var d = new Temporal.PlainDateTime(2020, 1, 15, 10, 20);"
                + "var e = d.with({hour: 11, minute: 30}); e.hour + ',' + e.minute"));
    }

    @Test
    public void test_with_calendar() {
        assertTrue(bool("var d = new Temporal.PlainDateTime(2020, 6, 15); d.withCalendar('iso8601').equals(d)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15).withCalendar('hebrew')"));
    }

    @Test
    public void test_with_plain_time() {
        assertEquals("2020-06-15T11:30:00",
                str("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime({hour: 11, minute: 30}).toString()"));
        assertEquals("2020-06-15T00:00:00",
                str("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime().toString()"));
        assertEquals("2020-06-15T05:00:00",
                str("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime(new Temporal.PlainTime(5)).toString()"));
    }

    // add/subtract carry a time overflow into the date, exercising the day-borrow logic
    @Test
    public void test_add_and_subtract_carry_into_date() {
        assertEquals("2020,1,16,1", str("var d = new Temporal.PlainDateTime(2020, 1, 15, 23);"
                + "var e = d.add({hours: 2}); e.year + ',' + e.month + ',' + e.day + ',' + e.hour"));
        assertEquals("2020,1,14,23", str("var d = new Temporal.PlainDateTime(2020, 1, 15, 0);"
                + "var e = d.subtract({hours: 1}); e.year + ',' + e.month + ',' + e.day + ',' + e.hour"));
        assertEquals("2023,2,28", str("var d = new Temporal.PlainDateTime(2023, 1, 31, 12);"
                + "var e = d.add({months: 1}); e.year + ',' + e.month + ',' + e.day"));
    }

    @Test
    public void test_add_mixed_sign_duration_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).add({years: 1, months: -1})"));
    }

    // until/since return a real Temporal.Duration
    @Test
    public void test_until_and_since_return_real_duration() {
        assertTrue(bool("new Temporal.PlainDateTime(2020, 1, 1)"
                + ".until(new Temporal.PlainDateTime(2020, 1, 2)) instanceof Temporal.Duration"));
        assertEquals(1, num("new Temporal.PlainDateTime(2020, 1, 1, 0)"
                + ".until(new Temporal.PlainDateTime(2020, 1, 1, 1), {largestUnit: 'hour'}).hours"));
        assertEquals(1, num("new Temporal.PlainDateTime(2020, 1, 1)"
                + ".until(new Temporal.PlainDateTime(2021, 1, 1), {largestUnit: 'year'}).years"));
        assertEquals(23, num("new Temporal.PlainDateTime(2020, 1, 1, 1)"
                + ".until(new Temporal.PlainDateTime(2020, 1, 2, 0), {largestUnit: 'year'}).hours"));
        assertEquals(-23, num("new Temporal.PlainDateTime(2020, 1, 1, 1)"
                + ".since(new Temporal.PlainDateTime(2020, 1, 2, 0), {largestUnit: 'day'}).hours"));
        assertEquals(-1, num("new Temporal.PlainDateTime(2020, 1, 1)"
                + ".since(new Temporal.PlainDateTime(2020, 1, 2), {largestUnit: 'day'}).days"));
    }

    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.PlainDateTime(2020, 1, 1).equals('2020-01-01T00:00:00')"));
        assertTrue(bool("!new Temporal.PlainDateTime(2020, 1, 1, 1).equals(new Temporal.PlainDateTime(2020, 1, 1))"));
    }

    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.PlainDateTime.compare("
                + "new Temporal.PlainDateTime(2020, 1, 1), new Temporal.PlainDateTime(2020, 1, 2))"));
        assertEquals(0, num("Temporal.PlainDateTime.compare("
                + "new Temporal.PlainDateTime(2020, 1, 1), new Temporal.PlainDateTime(2020, 1, 1))"));
    }

    @Test
    public void test_from() {
        assertEquals("2020,6,15,10,30", str("var d = Temporal.PlainDateTime.from('2020-06-15T10:30:00');"
                + "d.year + ',' + d.month + ',' + d.day + ',' + d.hour + ',' + d.minute"));
        assertEquals("2020,6,15,10",
                str("var d = Temporal.PlainDateTime.from({year: 2020, month: 6, day: 15, hour: 10});"
                        + "d.year + ',' + d.month + ',' + d.day + ',' + d.hour"));
        assertTrue(
                bool("var a = new Temporal.PlainDateTime(2020, 6, 15, 1); Temporal.PlainDateTime.from(a).equals(a)"));
    }

    @Test
    public void test_round() {
        assertEquals("2020-01-01T11:00:00",
                str("new Temporal.PlainDateTime(2020, 1, 1, 10, 40).round({smallestUnit: 'hour'}).toString()"));
        assertEquals("2020-01-02T00:00:00",
                str("new Temporal.PlainDateTime(2020, 1, 1, 23, 40).round({smallestUnit: 'hour'}).toString()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).round()"));
    }

    @Test
    public void test_to_plain_date_and_time() {
        assertTrue(bool(
                "new Temporal.PlainDateTime(2020, 6, 15, 10, 30)" + ".toPlainDate() instanceof Temporal.PlainDate"));
        assertTrue(bool(
                "new Temporal.PlainDateTime(2020, 6, 15, 10, 30)" + ".toPlainTime() instanceof Temporal.PlainTime"));
        assertEquals("2020-06-15", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toPlainDate().toString()"));
        assertEquals("10:30:00", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toPlainTime().toString()"));
    }

    @Test
    public void test_narrow_gap_projections() {
        assertEquals("2020-06", str("new Temporal.PlainDateTime(2020, 6, 15).toPlainYearMonth().toString()"));
        assertEquals("06-15", str("new Temporal.PlainDateTime(2020, 6, 15).toPlainMonthDay().toString()"));
        assertTrue(
                bool("new Temporal.PlainDateTime(2020, 6, 15).toPlainYearMonth() instanceof Temporal.PlainYearMonth"));
        assertTrue(bool("new Temporal.PlainDateTime(2020, 6, 15).toPlainMonthDay() instanceof Temporal.PlainMonthDay"));
        assertEquals("UTC", str("new Temporal.PlainDateTime(2020, 6, 15).toZonedDateTime('UTC').timeZoneId"));
        assertTrue(bool(
                "new Temporal.PlainDateTime(2020, 6, 15).toZonedDateTime('UTC') instanceof Temporal.ZonedDateTime"));
        assertEquals("2020-06-15T00:00:00+00:00[UTC]",
                str("new Temporal.PlainDateTime(2020, 6, 15).toZonedDateTime('UTC').toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15).toZonedDateTime()"));
    }

    @Test
    public void test_string_forms_and_iso_fields() {
        assertEquals("2020-06-15T10:30:00", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toString()"));
        assertEquals("2020-06-15T10:30:00[u-ca=iso8601]",
                str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toString({calendarName: 'always'})"));
        assertEquals("2020-06-15T10:30",
                str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30, 15).toString({smallestUnit: 'minute'})"));
        assertEquals("2020-06-15T10:30:00", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toJSON()"));
        assertEquals("2020-06-15T10:30:00", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toLocaleString()"));
        assertEquals("iso8601,15,6,2020", str("var f = new Temporal.PlainDateTime(2020, 6, 15).getISOFields();"
                + "f.calendar + ',' + f.isoDay + ',' + f.isoMonth + ',' + f.isoYear"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.PlainDateTime(2020, 6, 15)"));
    }

    @Test
    public void test_constructor_non_finite_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(NaN, 1, 1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(Infinity, 1, 1)"));
    }

    @Test
    public void test_week_calendar_accessors() {
        assertEquals("4,2", str("var d = new Temporal.PlainDateTime(2020, 1, 2);" + "d.dayOfWeek + ',' + d.dayOfYear"));
        assertEquals("1,2020",
                str("var d = new Temporal.PlainDateTime(2019, 12, 31);" + "d.weekOfYear + ',' + d.yearOfWeek"));
    }

    @Test
    public void test_to_date_time_rejects_non_object_like() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).equals(42)"));
    }

    @Test
    public void test_from_month_code_and_missing_fields() {
        assertEquals("2020,6,15", str("var d = Temporal.PlainDateTime.from({year: 2020, monthCode: 'M06', day: 15});"
                + "d.year + ',' + d.month + ',' + d.day"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.PlainDateTime.from({year: 2020, month: 7, monthCode: 'M06', day: 15})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({month: 6, day: 15})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, day: 15})"));
    }

    @Test
    public void test_invalid_month_codes_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, monthCode: 'X06', day: 15})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, monthCode: 'M13', day: 15})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, monthCode: 'M00', day: 15})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, monthCode: 'Mxx', day: 15})"));
    }

    @Test
    public void test_options_must_be_object() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).with({day: 2}, 42)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).round(42)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).toString(42)"));
    }

    @Test
    public void test_with_rejects_non_object_or_temporal_instance() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).with(42)"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainDateTime(2020, 1, 1).with(new Temporal.PlainDateTime(2020, 1, 2))"));
    }

    @Test
    public void test_with_month_code() {
        assertEquals(8, num("new Temporal.PlainDateTime(2020, 1, 1).with({monthCode: 'M08'}).month"));
    }

    @Test
    public void test_with_plain_time_from_string_and_invalid() {
        assertEquals("2020-06-15T11:30:00",
                str("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime('11:30').toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime(42)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime({})"));
    }

    @Test
    public void test_add_accepts_duration_instance_and_string() {
        assertEquals(2, num("new Temporal.PlainDateTime(2020, 1, 1).add(Temporal.Duration.from({hours: 2})).hour"));
        assertEquals(2, num("new Temporal.PlainDateTime(2020, 1, 1).add('PT2H').hour"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).add(42)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).add({hours: 1.5})"));
    }

    @Test
    public void test_difference_smallest_unit_larger_than_largest_unit_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1)"
                + ".until(new Temporal.PlainDateTime(2020, 1, 2), {largestUnit: 'day', smallestUnit: 'year'})"));
    }

    // Calendar-unit rounding (largestUnit/smallestUnit above "day") is implemented via
    // RelativeDurationMath, with the receiver itself as the implicit relativeTo anchor - no
    // RangeError, a real years/months breakdown instead.
    @Test
    public void test_difference_calendar_unit_rounding() {
        assertEquals("1,2", str("var d = new Temporal.PlainDateTime(2020, 1, 1)"
                + ".until(new Temporal.PlainDateTime(2021, 3, 1), {largestUnit: 'year', smallestUnit: 'month'});"
                + "d.years + ',' + d.months"));
    }

    // Exercises the "since" borrow-a-day branch where the date moves backward but the time-of-day
    // moves forward (the mirror image of test_until_and_since_return_real_duration's coverage).
    @Test
    public void test_since_borrows_day_when_time_moves_forward() {
        assertEquals(23, num("new Temporal.PlainDateTime(2020, 1, 2, 0)"
                + ".since(new Temporal.PlainDateTime(2020, 1, 1, 1), {largestUnit: 'day'}).hours"));
    }

    @Test
    public void test_since_negates_rounding_mode() {
        assertEquals(-15,
                num("new Temporal.PlainDateTime(2020, 1, 1, 0, 44, 30)"
                        + ".since(new Temporal.PlainDateTime(2020, 1, 1, 1), "
                        + "{smallestUnit: 'minute', roundingMode: 'ceil'}).minutes"));
        assertEquals(-16,
                num("new Temporal.PlainDateTime(2020, 1, 1, 0, 44, 30)"
                        + ".since(new Temporal.PlainDateTime(2020, 1, 1, 1), "
                        + "{smallestUnit: 'minute', roundingMode: 'floor'}).minutes"));
    }

    @Test
    public void test_round_missing_smallest_unit_and_invalid_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).round({})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1).round({smallestUnit: 'month'})"));
    }

    @Test
    public void test_round_string_shorthand_and_rounding_increment_validation() {
        assertEquals("2020-01-01T11:00:00",
                str("new Temporal.PlainDateTime(2020, 1, 1, 10, 40).round('hour').toString()"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainDateTime(2020, 1, 1, 10).round({smallestUnit: 'day', roundingIncrement: 2})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainDateTime(2020, 1, 1, 1).round({smallestUnit: 'hour', roundingIncrement: 5})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainDateTime(2020, 1, 1, 1).round({smallestUnit: 'hour', roundingIncrement: 24})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run(
                "new Temporal.PlainDateTime(2020, 1, 1, 1).round({smallestUnit: 'second', roundingIncrement: 0})"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 1, 1, 1)"
                + ".round({smallestUnit: 'second', roundingIncrement: 1000000001})"));
    }

    @Test
    public void test_round_exact_value_and_rounding_modes() {
        assertEquals("2020-01-01T10:00:00",
                str("new Temporal.PlainDateTime(2020, 1, 1, 10, 0, 0).round({smallestUnit: 'hour'}).toString()"));
        assertEquals("2020-01-01T12:34:57", str("new Temporal.PlainDateTime(2020, 1, 1, 12, 34, 56, 500)"
                + ".round({smallestUnit: 'second', roundingMode: 'ceil'}).toString()"));
        final var setup = "new Temporal.PlainDateTime(2020, 1, 1, 0, 0, 0, 0, 0, %d)"
                + ".round({smallestUnit: 'nanosecond', roundingIncrement: 10, roundingMode: '%s'}).nanosecond";
        assertEquals(20, num(String.format(setup, 15, "halfEven")));
        assertEquals(20, num(String.format(setup, 25, "halfEven")));
    }

    @Test
    public void test_to_string_second_or_smaller_unit_and_fractional_digits() {
        assertEquals("2020-06-15T10:30:15", str(
                "new Temporal.PlainDateTime(2020, 6, 15, 10, 30, 15, 500)" + ".toString({smallestUnit: 'second'})"));
        assertEquals("2020-06-15T10:30:15.5", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30, 15, 500)"
                + ".toString({fractionalSecondDigits: 'auto'})"));
        assertEquals("2020-06-15T10:30:15.5000", str(
                "new Temporal.PlainDateTime(2020, 6, 15, 10, 30, 15, 500)" + ".toString({fractionalSecondDigits: 4})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainDateTime(2020, 6, 15).toString({fractionalSecondDigits: 10})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15).toString({smallestUnit: 'hour'})"));
    }

    @Test
    public void test_to_zoned_date_time_object_timezone_and_invalid_zone() {
        assertEquals("UTC",
                str("new Temporal.PlainDateTime(2020, 6, 15).toZonedDateTime({timeZone: 'UTC'}).timeZoneId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15).toZonedDateTime('Not/AZone')"));
        assertTrue(bool("new Temporal.PlainDateTime(2020, 6, 15).toZonedDateTime('UTC').toString()"
                + ".indexOf('2020-06-15') === 0"));
    }

    @Test
    public void test_to_zoned_date_time_dst_gap_disambiguation() {
        // 2020-03-08 02:30 America/New_York is a nonexistent local time (spring-forward gap).
        assertEquals(3, num("new Temporal.PlainDateTime(2020, 3, 8, 2, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'later'}).hour"));
        assertEquals(1, num("new Temporal.PlainDateTime(2020, 3, 8, 2, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'earlier'}).hour"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 3, 8, 2, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'reject'})"));
    }

    // era/eraYear are always undefined for the ISO-8601-only calendar this engine implements
    @Test
    public void test_era_and_era_year_are_undefined() {
        assertTrue(bool("new Temporal.PlainDateTime(2020, 6, 15).era === undefined"));
        assertTrue(bool("new Temporal.PlainDateTime(2020, 6, 15).eraYear === undefined"));
    }
}
