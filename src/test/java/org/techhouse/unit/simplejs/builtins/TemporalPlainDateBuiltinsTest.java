package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.JsThrowException;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalPlainDateBuiltinsTest {
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
        assertEquals("2020,6,15",
                str("var d = new Temporal.PlainDate(2020, 6, 15); d.year + ',' + d.month + ',' + d.day"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 13, 1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 2, 30)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainDate(2020, 6, 15)"));
    }

    @Test
    public void test_constructor_calendar_validation() {
        assertEquals("iso8601", str("new Temporal.PlainDate(2020, 6, 15, 'iso8601').calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15, 'gregory')"));
    }

    @Test
    public void test_constructor_calendar_non_string_is_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15, 5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).withCalendar(5)"));
    }

    @Test
    public void test_from_fields_calendar_field_flexible() {
        assertEquals("iso8601",
                str("Temporal.PlainDate.from({year: 2020, month: 6, day: 15, calendar: 'iso8601'}).calendarId"));
        assertEquals("iso8601",
                str("Temporal.PlainDate.from({year: 2020, month: 6, day: 15, calendar: '2020-06-15[u-ca=iso8601]'})"
                        + ".calendarId"));
        assertEquals("iso8601", str("Temporal.PlainDate.from({year: 2020, month: 6, day: 15, "
                + "calendar: new Temporal.PlainDate(2020, 1, 1)}).calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, month: 6, day: 15, calendar: 'hebrew'})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, month: 6, day: 15, calendar: 5})"));
    }

    @Test
    public void test_from_plain_date_time_and_zoned_date_time_fast_paths() {
        assertEquals("2020-06-15",
                str("Temporal.PlainDate.from(new Temporal.PlainDateTime(2020, 6, 15, 10, 30)).toString()"));
        assertEquals("2020-06-15", str("Temporal.PlainDate.from(Temporal.ZonedDateTime.from("
                + "'2020-06-15T10:00:00-04:00[America/New_York]')).toString()"));
    }

    @Test
    public void test_type_identity() {
        assertEquals("object", str("typeof new Temporal.PlainDate(2020, 6, 15)"));
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15) instanceof Temporal.PlainDate"));
        assertEquals("[object Temporal.PlainDate]",
                str("Object.prototype.toString.call(new Temporal.PlainDate(2020, 6, 15))"));
    }

    @Test
    public void test_field_accessors() {
        assertEquals("2020,6,15,M06", str("var d = new Temporal.PlainDate(2020, 6, 15);"
                + "d.year + ',' + d.month + ',' + d.day + ',' + d.monthCode"));
        assertEquals(7, num("new Temporal.PlainDate(2020, 6, 15).daysInWeek"));
        assertEquals(12, num("new Temporal.PlainDate(2020, 6, 15).monthsInYear"));
        assertEquals(29, num("new Temporal.PlainDate(2020, 2, 1).daysInMonth"));
        assertEquals(28, num("new Temporal.PlainDate(2019, 2, 1).daysInMonth"));
        assertEquals(366, num("new Temporal.PlainDate(2020, 1, 1).daysInYear"));
        assertEquals(365, num("new Temporal.PlainDate(2019, 1, 1).daysInYear"));
        assertTrue(bool("new Temporal.PlainDate(2020, 1, 1).inLeapYear"));
        assertTrue(bool("!new Temporal.PlainDate(2019, 1, 1).inLeapYear"));
        assertEquals("iso8601", str("new Temporal.PlainDate(2020, 1, 1).calendarId"));
    }

    // 2019-12-31 falls in ISO week 1 of 2020 - a classic year-boundary week-numbering edge case
    @Test
    public void test_iso_week_numbering_year_boundary() {
        assertEquals("1,2020",
                str("var d = new Temporal.PlainDate(2019, 12, 31);" + "d.weekOfYear + ',' + d.yearOfWeek"));
        assertEquals(4, num("new Temporal.PlainDate(2020, 1, 2).dayOfWeek"));
        assertEquals(2, num("new Temporal.PlainDate(2020, 1, 2).dayOfYear"));
    }

    @Test
    public void test_accessor_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(Temporal.PlainDate.prototype, 'year')" + ".get.call({})"));
    }

    @Test
    public void test_method_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainDate.prototype.toString.call({})"));
    }

    @Test
    public void test_with() {
        assertEquals("2020,6,20", str("var d = new Temporal.PlainDate(2020, 1, 15);"
                + "var e = d.with({month: 6, day: 20}); e.year + ',' + e.month + ',' + e.day"));
        assertEquals("2020,2,29", str("var d = new Temporal.PlainDate(2020, 1, 31);"
                + "var e = d.with({month: 2}); e.year + ',' + e.month + ',' + e.day"));
    }

    @Test
    public void test_with_calendar() {
        assertTrue(bool("var d = new Temporal.PlainDate(2020, 6, 15); d.withCalendar('iso8601').equals(d)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).withCalendar('hebrew')"));
    }

    @Test
    public void test_add_and_subtract() {
        assertEquals("2023,2,28", str("var d = new Temporal.PlainDate(2023, 1, 31);"
                + "var e = d.add({months: 1}); e.year + ',' + e.month + ',' + e.day"));
        assertEquals("2020,2,29", str("var d = new Temporal.PlainDate(2020, 1, 31);"
                + "var e = d.add({months: 1}); e.year + ',' + e.month + ',' + e.day"));
        assertEquals("2020,2,1", str("var d = new Temporal.PlainDate(2020, 3, 1);"
                + "var e = d.subtract({months: 1}); e.year + ',' + e.month + ',' + e.day"));
        assertEquals("2021,1,1", str("var d = new Temporal.PlainDate(2020, 1, 1);"
                + "var e = d.add({years: 1}); e.year + ',' + e.month + ',' + e.day"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2023, 1, 31).add({months: 1}, {overflow: 'reject'})"));
    }

    @Test
    public void test_add_mixed_sign_duration_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 1, 1).add({years: 1, months: -1})"));
    }

    @Test
    public void test_until_and_since() {
        assertEquals(1, num("new Temporal.PlainDate(2020, 1, 1)"
                + ".until(new Temporal.PlainDate(2021, 1, 1), {largestUnit: 'year'}).years"));
        assertEquals(3, num("new Temporal.PlainDate(2020, 1, 1)"
                + ".until(new Temporal.PlainDate(2020, 4, 1), {largestUnit: 'month'}).months"));
        assertEquals(14, num("new Temporal.PlainDate(2020, 1, 1).until(new Temporal.PlainDate(2020, 1, 15)).days"));
        assertEquals(-14, num("new Temporal.PlainDate(2020, 1, 15).until(new Temporal.PlainDate(2020, 1, 1)).days"));
        assertEquals(14, num("new Temporal.PlainDate(2020, 1, 15).since(new Temporal.PlainDate(2020, 1, 1)).days"));
        assertEquals(0, num("new Temporal.PlainDate(2020, 1, 1).until(new Temporal.PlainDate(2020, 1, 1)).sign"));
    }

    @Test
    public void test_until_allows_day_increment_greater_than_one() {
        assertEquals(10, num("new Temporal.PlainDate(2020, 1, 1).until(new Temporal.PlainDate(2020, 1, 15), "
                + "{smallestUnit: 'day', roundingIncrement: 10, roundingMode: 'floor'}).days"));
    }

    @Test
    public void test_add_rejects_duration_like_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).add({})"));
    }

    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.PlainDate(2020, 1, 1).equals('2020-01-01')"));
        assertTrue(bool("!new Temporal.PlainDate(2020, 1, 1).equals(new Temporal.PlainDate(2020, 1, 2))"));
    }

    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.PlainDate.compare("
                + "new Temporal.PlainDate(2020, 1, 1), new Temporal.PlainDate(2020, 1, 2))"));
        assertEquals(0, num("Temporal.PlainDate.compare("
                + "new Temporal.PlainDate(2020, 1, 1), new Temporal.PlainDate(2020, 1, 1))"));
        assertEquals(1, num("Temporal.PlainDate.compare("
                + "new Temporal.PlainDate(2020, 1, 2), new Temporal.PlainDate(2020, 1, 1))"));
    }

    @Test
    public void test_from() {
        assertEquals("2020,6,15",
                str("var d = Temporal.PlainDate.from('2020-06-15');" + "d.year + ',' + d.month + ',' + d.day"));
        assertEquals("2020,6,15", str("var d = Temporal.PlainDate.from({year: 2020, month: 6, day: 15});"
                + "d.year + ',' + d.month + ',' + d.day"));
        assertEquals("2020,6,15", str("var d = Temporal.PlainDate.from({year: 2020, monthCode: 'M06', day: 15});"
                + "d.year + ',' + d.month + ',' + d.day"));
        assertTrue(bool("var a = new Temporal.PlainDate(2020, 6, 15); Temporal.PlainDate.from(a).equals(a)"));
    }

    @Test
    public void test_string_forms_and_iso_fields() {
        assertEquals("2020-06-15", str("new Temporal.PlainDate(2020, 6, 15).toString()"));
        assertEquals("2020-06-15[u-ca=iso8601]",
                str("new Temporal.PlainDate(2020, 6, 15).toString({calendarName: 'always'})"));
        assertEquals("2020-06-15", str("new Temporal.PlainDate(2020, 6, 15).toJSON()"));
        assertEquals("2020-06-15", str("new Temporal.PlainDate(2020, 6, 15).toLocaleString()"));
        assertEquals("iso8601,15,6,2020", str("var f = new Temporal.PlainDate(2020, 6, 15).getISOFields();"
                + "f.calendar + ',' + f.isoDay + ',' + f.isoMonth + ',' + f.isoYear"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.PlainDate(2020, 6, 15)"));
    }

    @Test
    public void test_real_type_projections() {
        assertEquals("2020-06", str("new Temporal.PlainDate(2020, 6, 15).toPlainYearMonth().toString()"));
        assertEquals("06-15", str("new Temporal.PlainDate(2020, 6, 15).toPlainMonthDay().toString()"));
        assertEquals("2020-06-15T00:00:00", str("new Temporal.PlainDate(2020, 6, 15).toPlainDateTime().toString()"));
        assertEquals("2020-06-15T01:02:03", str("new Temporal.PlainDate(2020, 6, 15)"
                + ".toPlainDateTime({hour: 1, minute: 2, second: 3}).toString()"));
        assertEquals("UTC", str("new Temporal.PlainDate(2020, 6, 15).toZonedDateTime('UTC').timeZoneId"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).toZonedDateTime()"));
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15).toPlainYearMonth() instanceof Temporal.PlainYearMonth"));
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15).toPlainMonthDay() instanceof Temporal.PlainMonthDay"));
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15).toPlainDateTime() instanceof Temporal.PlainDateTime"));
        assertTrue(
                bool("new Temporal.PlainDate(2020, 6, 15).toZonedDateTime('UTC') instanceof Temporal.ZonedDateTime"));
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15).until(new Temporal.PlainDate(2020, 6, 20)) instanceof "
                + "Temporal.Duration"));
    }

    // era/eraYear are always undefined for the ISO-8601-only calendar this engine implements
    @Test
    public void test_era_and_era_year_are_undefined() {
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15).era === undefined"));
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15).eraYear === undefined"));
    }

    @Test
    public void test_to_zoned_date_time_with_options_object() {
        assertEquals("2020-06-15T01:02:03+00:00[UTC]", str("new Temporal.PlainDate(2020, 6, 15)"
                + ".toZonedDateTime({timeZone: 'UTC', plainTime: {hour: 1, minute: 2, second: 3}}).toString()"));
        assertEquals("2020-06-15T00:00:00+00:00[UTC]",
                str("new Temporal.PlainDate(2020, 6, 15).toZonedDateTime({timeZone: 'UTC'}).toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).toZonedDateTime({})"));
    }

    @Test
    public void test_representable_range_limits() {
        assertEquals("-271821,4,19",
                str("var d = new Temporal.PlainDate(-271821, 4, 19); d.year + ',' + d.month + ',' + d.day"));
        assertEquals("275760,9,13",
                str("var d = new Temporal.PlainDate(275760, 9, 13); d.year + ',' + d.month + ',' + d.day"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(-271821, 4, 18)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(275760, 9, 14)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.PlainDate.from('-999999-01-01')"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(-271821, 4, 19).add({days: -1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(275760, 9, 13).add({days: 1})"));
    }

    @Test
    public void test_from_fields_month_and_day_must_be_positive() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, month: -1, day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, month: 1, day: -1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, month: 0, day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 1, 15).with({day: 0})"));
    }

    @Test
    public void test_from_fields_month_code_validation() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, monthCode: 5, day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, monthCode: 'M1', day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, monthCode: 'M13', day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, monthCode: 'M06L', day: 1})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainDate.from({year: 2020, month: 5, monthCode: 'M06', day: 1})"));
        assertEquals("2020,6,15", str("var d = Temporal.PlainDate.from({year: 2020, monthCode: 'M06', day: 15});"
                + "d.year + ',' + d.month + ',' + d.day"));
    }

    @Test
    public void test_with_rejects_invalid_argument_shapes() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).with(new Temporal.PlainDate(2021, 1, 1))"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).with({year: 2021, calendar: 'iso8601'})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).with({year: 2021, timeZone: 'UTC'})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).with({})"));
    }

    @Test
    public void test_add_balances_time_units_into_days() {
        assertEquals(19, num("new Temporal.PlainDate(1976, 11, 18).add({hours: 24}).day"));
        assertEquals(18, num("new Temporal.PlainDate(1976, 11, 18).add({hours: 23}).day"));
        assertEquals(1, num("new Temporal.PlainDate(2000, 5, 2).add('-PT24.5H').day"));
    }

    @Test
    public void test_to_plain_year_month_and_month_day_force_reference_fields() {
        assertEquals("2020-06-01[u-ca=iso8601]",
                str("new Temporal.PlainDate(2020, 6, 15).toPlainYearMonth().toString({calendarName: 'always'})"));
        assertEquals("1972-06-15[u-ca=iso8601]",
                str("new Temporal.PlainDate(2020, 6, 15).toPlainMonthDay().toString({calendarName: 'always'})"));
    }

    @Test
    public void test_to_plain_date_time_object_argument_handling() {
        assertEquals("23,59,23",
                str("var dt = new Temporal.PlainDate(2000, 5, 2)"
                        + ".toPlainDateTime({hour: 25, minute: 70, second: 23});"
                        + "dt.hour + ',' + dt.minute + ',' + dt.second"));
        assertEquals(59,
                num("new Temporal.PlainDate(2000, 5, 2).toPlainDateTime({hour: 23, minute: 59, second: 60}).second"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2000, 5, 2).toPlainDateTime({})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(-271821, 4, 19).toPlainDateTime()"));
        assertEquals(1, num("new Temporal.PlainDate(-271821, 4, 19).toPlainDateTime({nanosecond: 1}).nanosecond"));
    }

    @Test
    public void test_with_calendar_accepts_flexible_forms() {
        assertEquals("iso8601", str("new Temporal.PlainDate(2020, 6, 15).withCalendar('2020-01-01').calendarId"));
        assertEquals("iso8601", str("new Temporal.PlainDate(2020, 6, 15).withCalendar('15:23').calendarId"));
        assertEquals("iso8601", str("new Temporal.PlainDate(2020, 6, 15)"
                + ".withCalendar(new Temporal.PlainDateTime(2020, 1, 1, 10, 30)).calendarId"));
    }

    @Test
    public void test_reflect_construct_prototype_threading() {
        assertTrue(bool("var proto = {}; var f = function () {}; f.prototype = proto;"
                + "Object.getPrototypeOf(Reflect.construct(Temporal.PlainDate, [2020, 6, 15], f)) === proto"));
        assertThrows(JsThrowException.class,
                () -> Interpreter.run("var f = function () {}.bind();"
                        + "Object.defineProperty(f, 'prototype', { get() { throw new Error('boom'); } });"
                        + "Reflect.construct(Temporal.PlainDate, [2020, 6, 15], f)"));
    }

    @Test
    public void test_compare_uses_internal_slots_not_getters() {
        assertEquals(-1,
                num("class AvoidGettersDate extends Temporal.PlainDate {"
                        + "  get year() { throw new Error('should not be called'); }" + "}"
                        + "const one = new AvoidGettersDate(2000, 5, 2);"
                        + "const two = new AvoidGettersDate(2006, 3, 25);" + "Temporal.PlainDate.compare(one, two)"));
    }
}
