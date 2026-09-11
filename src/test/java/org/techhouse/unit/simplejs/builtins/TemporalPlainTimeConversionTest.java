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

public class TemporalPlainTimeConversionTest {
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
    public void test_default_constructor_fields_are_zero() {
        assertEquals("00:00:00", str("new Temporal.PlainTime().toString()"));
    }

    @Test
    public void test_constructor_with_all_fields() {
        assertEquals("12:30:15.1002", str("new Temporal.PlainTime(12, 30, 15, 100, 200, 0).toString()"));
    }

    @Test
    public void test_constructor_fields_round_trip() {
        assertEquals("01:02:03.004005006", str("new Temporal.PlainTime(1, 2, 3, 4, 5, 6).toString()"));
    }

    @Test
    public void test_to_string_tag() {
        assertEquals("[object Temporal.PlainTime]", str("Object.prototype.toString.call(new Temporal.PlainTime())"));
    }

    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime(1, 2, 3)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_hour() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(24)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(-1)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_minute_and_second() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 60)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 60)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_subsecond_fields() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 1000)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 0, 1000)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 0, 0, 1000)"));
    }

    @Test
    public void test_to_json_and_to_locale_string_match_to_string() {
        assertTrue(bool("var t = new Temporal.PlainTime(9, 5, 0); "
                + "t.toJSON() === t.toString() && t.toLocaleString() === t.toString()"));
    }

    @Test
    public void test_from_string() {
        assertEquals("12:30:00", str("Temporal.PlainTime.from('12:30:00').toString()"));
    }

    @Test
    public void test_from_plain_time_creates_a_new_instance() {
        assertTrue(bool("var a = new Temporal.PlainTime(1, 2, 3); var b = Temporal.PlainTime.from(a); "
                + "a !== b && a.equals(b)"));
    }

    // ToTemporalTime's fast paths for PlainDateTime/ZonedDateTime arguments bypass the generic
    // property-bag path entirely
    @Test
    public void test_from_plain_date_time_and_zoned_date_time_fast_paths() {
        assertEquals("10:30:00",
                str("Temporal.PlainTime.from(new Temporal.PlainDateTime(2020, 6, 15, 10, 30)).toString()"));
        assertEquals("10:00:00", str("Temporal.PlainTime.from("
                + "Temporal.ZonedDateTime.from('2020-06-15T10:00:00-04:00[America/New_York]')).toString()"));
    }

    @Test
    public void test_from_object_default_overflow_constrains() {
        assertEquals(23, num("Temporal.PlainTime.from({hour: 25}).hour"));
    }

    @Test
    public void test_from_object_reject_overflow_throws() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainTime.from({hour: 25}, {overflow: 'reject'})"));
    }

    @Test
    public void test_round_to_minute() {
        assertEquals("12:35:00", str("new Temporal.PlainTime(12, 34, 56).round({smallestUnit: 'minute'}).toString()"));
    }

    // toString() honours fractionalSecondDigits (numeric and "auto"), smallestUnit and roundingMode
    @Test
    public void test_to_string_fractional_second_digits() {
        assertEquals("00:00:00.500000000",
                str("new Temporal.PlainTime(0, 0, 0, 500).toString({fractionalSecondDigits: 9})"));
        assertEquals("00:00:00", str("new Temporal.PlainTime(0, 0, 0, 500).toString({fractionalSecondDigits: 0})"));
        assertEquals("00:00:00.5",
                str("new Temporal.PlainTime(0, 0, 0, 500).toString({fractionalSecondDigits: 'auto'})"));
    }

    @Test
    public void test_to_string_fractional_second_digits_invalid() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toString({fractionalSecondDigits: -1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toString({fractionalSecondDigits: 10})"));
    }

    @Test
    public void test_to_string_smallest_unit_minute() {
        assertEquals("12:34", str("new Temporal.PlainTime(12, 34, 56).toString({smallestUnit: 'minute'})"));
    }

    @Test
    public void test_to_string_smallest_unit_other() {
        assertEquals("12:34:57", str(
                "new Temporal.PlainTime(12, 34, 56, 500).toString({smallestUnit: 'second', roundingMode: 'ceil'})"));
        assertEquals("12:34:56.500",
                str("new Temporal.PlainTime(12, 34, 56, 500).toString({smallestUnit: 'millisecond'})"));
    }

    @Test
    public void test_to_string_rejects_day_smallest_unit() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toString({smallestUnit: 'hour'})"));
    }

    @Test
    public void test_to_string_requires_object_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).toString(5)"));
    }

    @Test
    public void test_until_largest_unit_auto_defaults_to_hour() {
        assertEquals(2, num("new Temporal.PlainTime(10, 0, 0).until(new Temporal.PlainTime(12, 30, 0), "
                + "{largestUnit: 'auto'}).hours"));
    }

    // from() rejects a time-like object with no recognized fields
    @Test
    public void test_from_rejects_object_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from({})"));
    }

    // toPlainDateTime requires a date-like object, and requires each of year/month/day
    @Test
    public void test_to_plain_date_time_requires_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime(5)"));
    }

    @Test
    public void test_to_plain_date_time_requires_year_month_day() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime({month: 1, day: 1})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime({year: 2024, day: 1})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).toPlainDateTime({year: 2024, month: 1})"));
    }

    @Test
    public void test_to_locale_string() {
        assertEquals("01:00:00", str("new Temporal.PlainTime(1).toLocaleString()"));
    }

    @Test
    public void test_from_plain_date_time_and_zoned_date_time() {
        assertEquals("10:30:00",
                str("Temporal.PlainTime.from(new Temporal.PlainDateTime(2020, 1, 1, 10, 30))" + ".toString()"));
        assertEquals("10:30:00", str("Temporal.PlainTime.from(Temporal.ZonedDateTime.from("
                + "'2020-01-01T10:30:00+00:00[UTC]')).toString()"));
    }

    @Test
    public void test_from_reduced_month_day_string_is_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from('12-31')"));
    }

    // Every ambiguous-with-a-date-form shape (extended/basic year-month, extended/basic month-day)
    // requires a leading 'T' to be accepted as a bare time string
    @Test
    public void test_from_rejects_every_ambiguous_date_like_shape() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from('2020-06')"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from('202006')"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from('1231')"));
    }

    // A 'T'-prefixed ambiguous-looking string is unambiguous and parses as a time
    @Test
    public void test_from_accepts_t_prefixed_ambiguous_looking_string() {
        assertEquals("12:31:00", str("Temporal.PlainTime.from('T12:31').toString()"));
    }

    // A shape that merely looks date-like but has an invalid month/day is not actually ambiguous -
    // it just fails to parse as a time on its own merits
    @Test
    public void test_from_rejects_invalid_looking_date_shape_as_a_bad_time() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from('13-31')"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.from('209913')"));
    }
}
