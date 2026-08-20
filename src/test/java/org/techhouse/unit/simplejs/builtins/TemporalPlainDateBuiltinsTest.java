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

    // The constructor requires `new` and rejects out-of-range fields (no constrain option here)
    @Test
    public void test_constructor_validation() {
        assertEquals("2020,6,15",
                str("var d = new Temporal.PlainDate(2020, 6, 15); d.year + ',' + d.month + ',' + d.day"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 13, 1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 2, 30)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainDate(2020, 6, 15)"));
    }

    // A calendar argument must be "iso8601" - any other calendar is out of scope for this engine
    @Test
    public void test_constructor_calendar_validation() {
        assertEquals("iso8601", str("new Temporal.PlainDate(2020, 6, 15, 'iso8601').calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15, 'gregory')"));
    }

    // A non-string calendar argument is a TypeError, not a RangeError - it never reaches the
    // calendar-identifier validation at all
    @Test
    public void test_constructor_calendar_non_string_is_type_error() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15, 5)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).withCalendar(5)"));
    }

    // The property-bag `calendar` field accepts a bare identifier, a full ISO string carrying (or
    // defaulting) a u-ca annotation, or a Temporal object (fast path, no field reads)
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

    // ToTemporalDate's fast paths for PlainDateTime/ZonedDateTime arguments bypass the generic
    // property-bag path entirely
    @Test
    public void test_from_plain_date_time_and_zoned_date_time_fast_paths() {
        assertEquals("2020-06-15",
                str("Temporal.PlainDate.from(new Temporal.PlainDateTime(2020, 6, 15, 10, 30)).toString()"));
        assertEquals("2020-06-15", str("Temporal.PlainDate.from(Temporal.ZonedDateTime.from("
                + "'2020-06-15T10:00:00-04:00[America/New_York]')).toString()"));
    }

    // typeof/instanceof/toStringTag
    @Test
    public void test_type_identity() {
        assertEquals("object", str("typeof new Temporal.PlainDate(2020, 6, 15)"));
        assertTrue(bool("new Temporal.PlainDate(2020, 6, 15) instanceof Temporal.PlainDate"));
        assertEquals("[object Temporal.PlainDate]",
                str("Object.prototype.toString.call(new Temporal.PlainDate(2020, 6, 15))"));
    }

    // Field accessors, including the ISO week-numbering and leap-year edges
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

    // A field-accessor getter brand-checks its receiver
    @Test
    public void test_accessor_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(Temporal.PlainDate.prototype, 'year')" + ".get.call({})"));
    }

    // A prototype method brand-checks its receiver
    @Test
    public void test_method_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainDate.prototype.toString.call({})"));
    }

    // with() overrides only the given fields and re-regulates the result
    @Test
    public void test_with() {
        assertEquals("2020,6,20", str("var d = new Temporal.PlainDate(2020, 1, 15);"
                + "var e = d.with({month: 6, day: 20}); e.year + ',' + e.month + ',' + e.day"));
        assertEquals("2020,2,29", str("var d = new Temporal.PlainDate(2020, 1, 31);"
                + "var e = d.with({month: 2}); e.year + ',' + e.month + ',' + e.day"));
    }

    // withCalendar is an identity operation for the only supported calendar, and rejects any other
    @Test
    public void test_with_calendar() {
        assertTrue(bool("var d = new Temporal.PlainDate(2020, 6, 15); d.withCalendar('iso8601').equals(d)"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).withCalendar('hebrew')"));
    }

    // add/subtract carry month/day overflow the same way the constructor's `overflow` option does
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

    // A duration-like argument with mixed signs is a RangeError
    @Test
    public void test_add_mixed_sign_duration_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 1, 1).add({years: 1, months: -1})"));
    }

    // until/since compute a calendar difference broken down by largestUnit
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

    // until()/since() round a computed Duration, so a day-unit increment greater than 1 is valid
    // (unlike round(), which rounds a wall-clock reading and only ever accepts an increment of 1 for
    // "day")
    @Test
    public void test_until_allows_day_increment_greater_than_one() {
        assertEquals(10, num("new Temporal.PlainDate(2020, 1, 1).until(new Temporal.PlainDate(2020, 1, 15), "
                + "{smallestUnit: 'day', roundingIncrement: 10, roundingMode: 'floor'}).days"));
    }

    // A duration-like object with none of the ten recognized properties present is a TypeError
    @Test
    public void test_add_rejects_duration_like_with_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).add({})"));
    }

    // equals compares calendar date, and accepts any ToTemporalDate-convertible value
    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.PlainDate(2020, 1, 1).equals('2020-01-01')"));
        assertTrue(bool("!new Temporal.PlainDate(2020, 1, 1).equals(new Temporal.PlainDate(2020, 1, 2))"));
    }

    // compare() static
    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.PlainDate.compare("
                + "new Temporal.PlainDate(2020, 1, 1), new Temporal.PlainDate(2020, 1, 2))"));
        assertEquals(0, num("Temporal.PlainDate.compare("
                + "new Temporal.PlainDate(2020, 1, 1), new Temporal.PlainDate(2020, 1, 1))"));
        assertEquals(1, num("Temporal.PlainDate.compare("
                + "new Temporal.PlainDate(2020, 1, 2), new Temporal.PlainDate(2020, 1, 1))"));
    }

    // from() accepts a PlainDate, an ISO string, or a date-like object (year+month/monthCode+day)
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

    // toString/toJSON/toLocaleString/getISOFields
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

    // valueOf always throws, per spec - both directly and through implicit numeric coercion
    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.PlainDate(2020, 6, 15)"));
    }

    // toPlainYearMonth/toPlainMonthDay/toPlainDateTime/toZonedDateTime/until/since return real
    // Temporal instances, not duck-typed plain objects
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

    // toZonedDateTime accepts a {timeZone, plainTime} options object, with plainTime optional
    @Test
    public void test_to_zoned_date_time_with_options_object() {
        assertEquals("2020-06-15T01:02:03+00:00[UTC]", str("new Temporal.PlainDate(2020, 6, 15)"
                + ".toZonedDateTime({timeZone: 'UTC', plainTime: {hour: 1, minute: 2, second: 3}}).toString()"));
        assertEquals("2020-06-15T00:00:00+00:00[UTC]",
                str("new Temporal.PlainDate(2020, 6, 15).toZonedDateTime({timeZone: 'UTC'}).toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainDate(2020, 6, 15).toZonedDateTime({})"));
    }
}
