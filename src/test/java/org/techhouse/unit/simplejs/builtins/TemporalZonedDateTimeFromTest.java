package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalZonedDateTimeFromTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.ZonedDateTime(0n, 'UTC')"));
    }

    @Test
    public void test_constructor_requires_bigint_epoch() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0, 'UTC')"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime()"));
    }

    @Test
    public void test_constructor_requires_time_zone() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n)"));
    }

    @Test
    public void test_constructor_rejects_invalid_time_zone() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'Not/AZone')"));
    }

    @Test
    public void test_constructor_rejects_non_iso8601_calendar() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC', 'hebrew')"));
    }

    // A non-string timeZone/calendar argument is a TypeError, not a RangeError
    @Test
    public void test_constructor_non_string_arguments_are_type_errors() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 5)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC', 5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withCalendar(5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.ZonedDateTime(0n, 'UTC').withTimeZone(5)"));
    }

    // A calendar annotation on an ISO string parsed by from() is validated the same way a bare
    // identifier is
    @Test
    public void test_from_string_validates_calendar_annotation() {
        assertEquals("iso8601",
                str("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00[UTC][u-ca=iso8601]').calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00[UTC][u-ca=hebrew]')"));
    }

    // The property-bag `timeZone` field accepts a full ISO date-time string carrying a bracketed
    // time zone (ToTemporalTimeZoneIdentifier's flexible form), not just a bare identifier
    @Test
    public void test_from_fields_time_zone_field_flexible() {
        assertEquals("America/New_York", str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, "
                + "timeZone: '2020-01-01T00:00:00-05:00[America/New_York]'}).timeZoneId"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 5})"));
    }

    // The property-bag `calendar` field accepts a bare identifier, a full ISO string carrying (or
    // defaulting) a u-ca annotation, or a Temporal object (fast path)
    @Test
    public void test_from_fields_calendar_field_flexible() {
        assertEquals("iso8601",
                str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', calendar: 'iso8601'})"
                        + ".calendarId"));
        assertEquals("iso8601", str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', "
                + "calendar: '2020-06-15[u-ca=iso8601]'}).calendarId"));
        assertEquals("iso8601", str("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', "
                + "calendar: new Temporal.PlainDate(2020, 1, 1)}).calendarId"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run(
                "Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', calendar: 'hebrew'})"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC', calendar: 5})"));
    }

    @Test
    public void test_constructor_accepts_offset_time_zone() {
        assertEquals("+01:00", str("new Temporal.ZonedDateTime(0n, '+01:00').timeZoneId"));
        assertEquals(1, num("new Temporal.ZonedDateTime(0n, '+01:00').hour"));
    }

    @Test
    public void test_from_accepts_instance_string_and_object() {
        assertEquals("UTC", str("Temporal.ZonedDateTime.from(new Temporal.ZonedDateTime(0n, 'UTC')).timeZoneId"));
        assertEquals("1970-01-01T00:00:00+00:00[UTC]",
                str("Temporal.ZonedDateTime.from('1970-01-01T00:00:00+00:00[UTC]').toString()"));
        assertEquals(2020, num("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15, timeZone: 'UTC'}).year"));
    }

    @Test
    public void test_from_rejects_invalid() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.ZonedDateTime.from(42)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, month: 6, day: 15})"));
    }

    @Test
    public void test_from_accepts_wrapped_instance() {
        final var script = "var Ctor = function() {};"
                + "var z = Reflect.construct(Temporal.ZonedDateTime, [0n, 'UTC'], Ctor);"
                + "var copy = Temporal.ZonedDateTime.from(z);"
                + "copy.timeZoneId + ',' + (Object.getPrototypeOf(copy) === Ctor.prototype)";
        assertEquals("UTC,false", str(script));
    }

    @Test
    public void test_constructor_accepts_explicit_iso8601_calendar() {
        assertEquals("iso8601", str("new Temporal.ZonedDateTime(0n, 'UTC', 'iso8601').calendarId"));
    }

    @Test
    public void test_from_fields_requires_year_and_day() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({month: 1, day: 1, timeZone: 'UTC'})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, month: 1, timeZone: 'UTC'})"));
    }

    @Test
    public void test_from_fields_month_code() {
        assertEquals(6,
                num("Temporal.ZonedDateTime.from({year: 2020, monthCode: 'M06', day: 1, timeZone: 'UTC'}).month"));
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year: 2020, monthCode: 'M06', month: 7, day: 1, timeZone: 'UTC'})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year: 2020, day: 1, timeZone: 'UTC'})"));
    }

    @Test
    public void test_from_string_invalid_time_zone_throws_range_error() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from('2020-06-15T00:00:00[Not/AZone]')"));
    }

    @Test
    public void test_from_offset_option_use_trusts_explicit_offset() {
        // "use" trusts the explicit +05:00 offset to compute the exact instant directly (2020-01-01
        // 00:00 minus +05:00 = 2019-12-31T19:00:00 UTC), rather than resolving 00:00 against what the
        // UTC zone itself observes (which would give the wall time unchanged, per "ignore").
        assertEquals("2019-12-31T19:00:00+00:00[UTC]",
                str("Temporal.ZonedDateTime.from({year:2020,month:1,day:1,hour:0,offset:'+05:00',timeZone:'UTC'}, "
                        + "{offset:'use'}).toString()"));
    }

    @Test
    public void test_from_offset_option_ignore_uses_wall_time() {
        // "ignore" discards the (mismatched) explicit offset, falling back to disambiguation with UTC.
        assertEquals("2020-01-01T00:00:00+00:00[UTC]",
                str("Temporal.ZonedDateTime.from({year:2020,month:1,day:1,hour:0,offset:'+05:00',timeZone:'UTC'}, "
                        + "{offset:'ignore'}).toString()"));
    }

    @Test
    public void test_from_offset_option_prefer_falls_back_on_mismatch() {
        assertEquals("2020-01-01T00:00:00+00:00[UTC]",
                str("Temporal.ZonedDateTime.from({year:2020,month:1,day:1,hour:0,offset:'+05:00',timeZone:'UTC'}, "
                        + "{offset:'prefer'}).toString()"));
    }

    @Test
    public void test_from_offset_option_reject_default_throws_on_mismatch() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year:2020,month:1,day:1,hour:0,offset:'+05:00',timeZone:'UTC'})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year:2020,month:1,day:1,hour:0,offset:'+05:00',"
                        + "timeZone:'UTC'}, {offset:'reject'})"));
    }

    @Test
    public void test_from_offset_option_matching_offset_never_throws() {
        assertEquals("2020-01-01T05:00:00+05:00[+05:00]",
                str("Temporal.ZonedDateTime.from({year:2020,month:1,day:1,hour:5,offset:'+05:00',"
                        + "timeZone:'+05:00'}, {offset:'reject'}).toString()"));
    }

    @Test
    public void test_from_offset_option_invalid_value_throws() {
        assertThrows(RangeErrorException.class, () -> Interpreter
                .run("Temporal.ZonedDateTime.from({year:2020,month:1,day:1,timeZone:'UTC'}, " + "{offset:'bogus'})"));
    }

    @Test
    public void test_from_month_code_wrong_type_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year:2020,monthCode:5,day:1,timeZone:'UTC'})"));
    }

    @Test
    public void test_from_month_code_numeric_out_of_range_throws() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year:2020,monthCode:'M99',day:1,timeZone:'UTC'})"));
    }

    @Test
    public void test_from_requires_month_or_month_code() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year:2020,day:1,timeZone:'UTC'})"));
    }

    @Test
    public void test_from_rejects_non_positive_day_and_month() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year:2020,month:1,day:0,timeZone:'UTC'})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.ZonedDateTime.from({year:2020,month:0,day:1,timeZone:'UTC'})"));
    }

    @Test
    public void test_from_time_zone_accepts_zoned_date_time_instance() {
        assertEquals("UTC", str("Temporal.ZonedDateTime.from({year:2020,month:5,day:2,"
                + "timeZone: new Temporal.ZonedDateTime(0n, 'UTC')}).timeZoneId"));
    }
}
