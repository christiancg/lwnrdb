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

public class TemporalPlainYearMonthBuiltinsTest {
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
        assertEquals("2020,6", str("var d = new Temporal.PlainYearMonth(2020, 6); d.year + ',' + d.month"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 13)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainYearMonth(2020, 6)"));
    }

    @Test
    public void test_constructor_calendar_and_reference_day() {
        assertEquals("iso8601", str("new Temporal.PlainYearMonth(2020, 6, 'iso8601').calendarId"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 6, 'gregory')"));
        assertEquals(15, num("new Temporal.PlainYearMonth(2020, 6, 'iso8601', 15).getISOFields().isoDay"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 2, 'iso8601', 30)"));
    }

    @Test
    public void test_type_identity() {
        assertEquals("object", str("typeof new Temporal.PlainYearMonth(2020, 6)"));
        assertTrue(bool("new Temporal.PlainYearMonth(2020, 6) instanceof Temporal.PlainYearMonth"));
        assertEquals("[object Temporal.PlainYearMonth]",
                str("Object.prototype.toString.call(new Temporal.PlainYearMonth(2020, 6))"));
    }

    @Test
    public void test_field_accessors() {
        assertEquals("2020,6,M06",
                str("var d = new Temporal.PlainYearMonth(2020, 6);" + "d.year + ',' + d.month + ',' + d.monthCode"));
        assertEquals(30, num("new Temporal.PlainYearMonth(2020, 6).daysInMonth"));
        assertEquals(366, num("new Temporal.PlainYearMonth(2020, 1).daysInYear"));
        assertEquals(365, num("new Temporal.PlainYearMonth(2019, 1).daysInYear"));
        assertEquals(12, num("new Temporal.PlainYearMonth(2020, 1).monthsInYear"));
        assertTrue(bool("new Temporal.PlainYearMonth(2020, 1).inLeapYear"));
        assertTrue(bool("!new Temporal.PlainYearMonth(2019, 1).inLeapYear"));
        assertEquals("iso8601", str("new Temporal.PlainYearMonth(2020, 1).calendarId"));
    }

    @Test
    public void test_accessor_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(Temporal.PlainYearMonth.prototype, 'year')" + ".get.call({})"));
    }

    @Test
    public void test_method_brand_check() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainYearMonth.prototype.toString.call({})"));
    }

    @Test
    public void test_with() {
        assertEquals("2020,2", str("var d = new Temporal.PlainYearMonth(2020, 1);"
                + "var e = d.with({month: 2}); e.year + ',' + e.month"));
        assertEquals("2021,1", str("var d = new Temporal.PlainYearMonth(2020, 1);"
                + "var e = d.with({year: 2021}); e.year + ',' + e.month"));
    }

    @Test
    public void test_add_and_subtract() {
        assertEquals("2021,2", str("var d = new Temporal.PlainYearMonth(2020, 1);"
                + "var e = d.add({months: 13}); e.year + ',' + e.month"));
        assertEquals("2020,2", str("var d = new Temporal.PlainYearMonth(2020, 3);"
                + "var e = d.subtract({months: 1}); e.year + ',' + e.month"));
        assertEquals("2021,1", str(
                "var d = new Temporal.PlainYearMonth(2020, 1);" + "var e = d.add({years: 1}); e.year + ',' + e.month"));
    }

    @Test
    public void test_add_mixed_sign_duration_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 1).add({years: 1, months: -1})"));
    }

    // add/subtract accept a real Temporal.Duration instance directly (Duration is now a merged type)
    @Test
    public void test_add_accepts_real_duration() {
        assertEquals("2021,1", str("var d = new Temporal.PlainYearMonth(2020, 1);"
                + "var e = d.add(new Temporal.Duration(1)); e.year + ',' + e.month"));
    }

    @Test
    public void test_until_and_since() {
        assertEquals(1,
                num("new Temporal.PlainYearMonth(2020, 1)" + ".until(new Temporal.PlainYearMonth(2021, 1)).years"));
        assertEquals(3, num("new Temporal.PlainYearMonth(2020, 1)"
                + ".until(new Temporal.PlainYearMonth(2020, 4), {largestUnit: 'month'}).months"));
        assertEquals(3, num("new Temporal.PlainYearMonth(2020, 4)"
                + ".since(new Temporal.PlainYearMonth(2020, 1), {largestUnit: 'month'}).months"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 1)"
                + ".until(new Temporal.PlainYearMonth(2020, 4), {largestUnit: 'day'})"));
    }

    // until/since return a real Temporal.Duration instance
    @Test
    public void test_until_returns_real_duration() {
        assertTrue(bool("new Temporal.PlainYearMonth(2020, 1)"
                + ".until(new Temporal.PlainYearMonth(2021, 1)) instanceof Temporal.Duration"));
    }

    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.PlainYearMonth(2020, 6).equals('2020-06')"));
        assertTrue(bool("!new Temporal.PlainYearMonth(2020, 6).equals(new Temporal.PlainYearMonth(2020, 7))"));
        // Differing referenceISODay makes two otherwise-identical year-months unequal, per spec
        assertTrue(bool("!new Temporal.PlainYearMonth(2020, 6, 'iso8601', 15)"
                + ".equals(new Temporal.PlainYearMonth(2020, 6))"));
    }

    @Test
    public void test_compare() {
        assertEquals(-1, num("Temporal.PlainYearMonth.compare("
                + "new Temporal.PlainYearMonth(2020, 1), new Temporal.PlainYearMonth(2020, 2))"));
        assertEquals(0, num("Temporal.PlainYearMonth.compare("
                + "new Temporal.PlainYearMonth(2020, 1), new Temporal.PlainYearMonth(2020, 1))"));
        assertEquals(1, num("Temporal.PlainYearMonth.compare("
                + "new Temporal.PlainYearMonth(2020, 2), new Temporal.PlainYearMonth(2020, 1))"));
    }

    @Test
    public void test_from() {
        assertEquals("2020,6", str("var d = Temporal.PlainYearMonth.from('2020-06'); d.year + ',' + d.month"));
        assertEquals(1, num("Temporal.PlainYearMonth.from('2020-06').getISOFields().isoDay"));
        assertEquals(15, num("Temporal.PlainYearMonth.from('2020-06-15').getISOFields().isoDay"));
        assertEquals("2020,6",
                str("var d = Temporal.PlainYearMonth.from({year: 2020, month: 6}); d.year + ',' + d.month"));
        assertEquals("2020,6",
                str("var d = Temporal.PlainYearMonth.from({year: 2020, monthCode: 'M06'}); d.year + ',' + d.month"));
        assertTrue(bool("var a = new Temporal.PlainYearMonth(2020, 6); Temporal.PlainYearMonth.from(a).equals(a)"));
    }

    @Test
    public void test_string_forms_and_iso_fields() {
        assertEquals("2020-06", str("new Temporal.PlainYearMonth(2020, 6).toString()"));
        assertEquals("2020-06[u-ca=iso8601]",
                str("new Temporal.PlainYearMonth(2020, 6).toString({calendarName: 'always'})"));
        assertEquals("2020-06", str("new Temporal.PlainYearMonth(2020, 6).toJSON()"));
        assertEquals("2020-06", str("new Temporal.PlainYearMonth(2020, 6).toLocaleString()"));
        assertEquals("iso8601,1,6,2020", str("var f = new Temporal.PlainYearMonth(2020, 6).getISOFields();"
                + "f.calendar + ',' + f.isoDay + ',' + f.isoMonth + ',' + f.isoYear"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 6).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.PlainYearMonth(2020, 6)"));
    }

    @Test
    public void test_to_plain_date() {
        assertEquals("2020-06-15", str("new Temporal.PlainYearMonth(2020, 6).toPlainDate({day: 15}).toString()"));
        assertEquals("2020-02-29", str("new Temporal.PlainYearMonth(2020, 2).toPlainDate({day: 30}).toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 6).toPlainDate()"));
    }

    @Test
    public void test_constructor_non_finite_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainYearMonth(NaN, 1)"));
    }

    @Test
    public void test_from_rejects_non_convertible_and_missing_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainYearMonth.from(42)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainYearMonth.from({year: 2020})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainYearMonth.from({year: 2020, month: 7, monthCode: 'M06'})"));
    }

    @Test
    public void test_invalid_month_codes_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainYearMonth.from({year: 2020, monthCode: 'X06'})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainYearMonth.from({year: 2020, monthCode: 'M13'})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainYearMonth.from({year: 2020, monthCode: 'Mxx'})"));
    }

    @Test
    public void test_options_must_be_object() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 1).with({month: 2}, 42)"));
    }

    @Test
    public void test_with_rejects_non_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainYearMonth(2020, 1).with(42)"));
    }

    @Test
    public void test_with_month_code() {
        assertEquals(8, num("new Temporal.PlainYearMonth(2020, 1).with({monthCode: 'M08'}).month"));
    }

    @Test
    public void test_until_largest_unit_auto_and_explicit_overflow() {
        assertEquals(1, num("new Temporal.PlainYearMonth(2020, 1)"
                + ".until(new Temporal.PlainYearMonth(2021, 1), {largestUnit: 'auto'}).years"));
        assertEquals("2020,2", str("var d = new Temporal.PlainYearMonth(2020, 1);"
                + "var e = d.with({month: 2}, {overflow: 'reject'}); e.year + ',' + e.month"));
    }
}
