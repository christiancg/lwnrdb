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

public class TemporalPlainDateTimeConversionTest {
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

    // A non-string calendar argument is a TypeError, not a RangeError
    @Test
    public void test_constructor_calendar_non_string_is_type_error() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15, 0, 0, 0, 0, 0, 0, 5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15).withCalendar(5)"));
    }

    // The property-bag `calendar` field accepts a bare identifier, a full ISO string carrying (or
    // defaulting) a u-ca annotation, or a Temporal object (fast path)
    @Test
    public void test_from_fields_calendar_field_flexible() {
        assertEquals("iso8601",
                str("Temporal.PlainDateTime.from({year: 2020, month: 6, day: 15, calendar: 'iso8601'}).calendarId"));
        assertEquals("iso8601",
                str("Temporal.PlainDateTime.from({year: 2020, month: 6, day: 15, calendar: '2020-06-15[u-ca=iso8601]'})"
                        + ".calendarId"));
        assertEquals("iso8601", str("Temporal.PlainDateTime.from({year: 2020, month: 6, day: 15, "
                + "calendar: new Temporal.PlainDate(2020, 1, 1)}).calendarId"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.PlainDateTime.from({year: 2020, month: 6, day: 15, calendar: 'hebrew'})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, month: 6, day: 15, calendar: 5})"));
    }

    // ToTemporalDateTime's fast paths for PlainDate/ZonedDateTime arguments bypass the generic
    // property-bag path entirely - a PlainDate's time defaults to midnight
    @Test
    public void test_from_plain_date_and_zoned_date_time_fast_paths() {
        assertEquals("2020-06-15T00:00:00",
                str("Temporal.PlainDateTime.from(new Temporal.PlainDate(2020, 6, 15)).toString()"));
        assertEquals("2020-06-15T10:00:00", str("Temporal.PlainDateTime.from("
                + "Temporal.ZonedDateTime.from('2020-06-15T10:00:00-04:00[America/New_York]')).toString()"));
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
    public void test_to_plain_date_and_time() {
        assertTrue(bool(
                "new Temporal.PlainDateTime(2020, 6, 15, 10, 30)" + ".toPlainDate() instanceof Temporal.PlainDate"));
        assertTrue(bool(
                "new Temporal.PlainDateTime(2020, 6, 15, 10, 30)" + ".toPlainTime() instanceof Temporal.PlainTime"));
        assertEquals("2020-06-15", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toPlainDate().toString()"));
        assertEquals("10:30:00", str("new Temporal.PlainDateTime(2020, 6, 15, 10, 30).toPlainTime().toString()"));
    }

    @Test
    public void test_constructor_non_finite_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(NaN, 1, 1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(Infinity, 1, 1)"));
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
    public void test_with_plain_time_from_string_and_invalid() {
        assertEquals("2020-06-15T11:30:00",
                str("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime('11:30').toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime(42)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDateTime(2020, 6, 15, 10).withPlainTime({})"));
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
        assertEquals(3, num("new Temporal.PlainDateTime(2020, 3, 8, 2, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'compatible'}).hour"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 3, 8, 2, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'bogus'})"));
    }

    @Test
    public void test_to_zoned_date_time_dst_fold_disambiguation() {
        // 2020-11-01 01:30 America/New_York is an ambiguous local time (fall-back fold).
        assertEquals(1, num("new Temporal.PlainDateTime(2020, 11, 1, 1, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'later'}).hour"));
        assertEquals(1, num("new Temporal.PlainDateTime(2020, 11, 1, 1, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'earlier'}).hour"));
        assertEquals(1, num("new Temporal.PlainDateTime(2020, 11, 1, 1, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'compatible'}).hour"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDateTime(2020, 11, 1, 1, 30)"
                + ".toZonedDateTime('America/New_York', {disambiguation: 'reject'})"));
    }

    @Test
    public void test_to_date_time_from_string_with_calendar_annotation() {
        assertEquals("iso8601", str("Temporal.PlainDateTime.from('2020-06-15[u-ca=iso8601]').calendarId"));
    }

    @Test
    public void test_from_fields_day_required() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, month: 6})"));
    }

    @Test
    public void test_from_fields_non_positive_day_or_month() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, month: 6, day: 0})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, month: 0, day: 1})"));
    }

    @Test
    public void test_from_fields_month_code_validation() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, monthCode: 5, day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, monthCode: 'X08', day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDateTime.from({year: 2020, monthCode: 'M13', day: 1})"));
    }

    @Test
    public void test_to_string_fractional_second_digits_invalid() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainDateTime(2020, 6, 15, 1, 2, 3).toString({fractionalSecondDigits: NaN})"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("new Temporal.PlainDateTime(2020, 6, 15, 1, 2, 3).toString({fractionalSecondDigits: 'bogus'})"));
    }
}
