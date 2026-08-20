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

public class TemporalPlainMonthDayBuiltinsTest {
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
        assertEquals("11,30",
                str("var d = new Temporal.PlainMonthDay(11, 30); d.monthCode.substring(1) + ',' + d.day"));
        assertEquals(29, num("new Temporal.PlainMonthDay(2, 29).day"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainMonthDay(2, 30)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainMonthDay(13, 1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainMonthDay(11, 30)"));
    }

    @Test
    public void test_constructor_calendar_and_reference_year() {
        assertEquals("iso8601", str("new Temporal.PlainMonthDay(11, 30, 'iso8601').calendarId"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainMonthDay(11, 30, 'gregory')"));
        assertEquals(2000, num("new Temporal.PlainMonthDay(2, 29, 'iso8601', 2000).getISOFields().isoYear"));
        assertEquals(1972, num("new Temporal.PlainMonthDay(11, 30).getISOFields().isoYear"));
    }

    @Test
    public void test_type_identity() {
        assertEquals("object", str("typeof new Temporal.PlainMonthDay(11, 30)"));
        assertTrue(bool("new Temporal.PlainMonthDay(11, 30) instanceof Temporal.PlainMonthDay"));
        assertEquals("[object Temporal.PlainMonthDay]",
                str("Object.prototype.toString.call(new Temporal.PlainMonthDay(11, 30))"));
    }

    // No numeric `month` accessor exists per spec - only monthCode/day/calendarId
    @Test
    public void test_field_accessors() {
        assertEquals("M11,30,iso8601",
                str("var d = new Temporal.PlainMonthDay(11, 30);" + "d.monthCode + ',' + d.day + ',' + d.calendarId"));
        assertEquals("undefined", str("typeof new Temporal.PlainMonthDay(11, 30).month"));
    }

    @Test
    public void test_accessor_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run(
                "Object.getOwnPropertyDescriptor(Temporal.PlainMonthDay.prototype, 'monthCode')" + ".get.call({})"));
    }

    @Test
    public void test_method_brand_check() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Temporal.PlainMonthDay.prototype.toString.call({})"));
    }

    // No add/subtract/until/since/compare exist per spec (a bare month-day cannot be arithmetic'd
    // without a year)
    @Test
    public void test_no_arithmetic_or_compare_surface() {
        assertEquals("undefined", str("typeof new Temporal.PlainMonthDay(11, 30).add"));
        assertEquals("undefined", str("typeof new Temporal.PlainMonthDay(11, 30).until"));
        assertEquals("undefined", str("typeof Temporal.PlainMonthDay.compare"));
    }

    @Test
    public void test_with() {
        assertEquals("11,25", str("var d = new Temporal.PlainMonthDay(11, 30);"
                + "var e = d.with({day: 25}); e.monthCode.substring(1) + ',' + e.day"));
        assertEquals("06,15", str("var d = new Temporal.PlainMonthDay(11, 30);"
                + "var e = d.with({monthCode: 'M06', day: 15}); e.monthCode.substring(1) + ',' + e.day"));
    }

    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.PlainMonthDay(11, 30).equals('11-30')"));
        assertTrue(bool("!new Temporal.PlainMonthDay(11, 30).equals(new Temporal.PlainMonthDay(11, 29))"));
        // Differing referenceISOYear makes two otherwise-identical month-days unequal, per spec
        assertTrue(bool(
                "!new Temporal.PlainMonthDay(2, 29, 'iso8601', 2000)" + ".equals(new Temporal.PlainMonthDay(2, 29))"));
    }

    @Test
    public void test_from() {
        assertEquals("11,30",
                str("var d = Temporal.PlainMonthDay.from('11-30');" + "d.monthCode.substring(1) + ',' + d.day"));
        assertEquals("11,30",
                str("var d = Temporal.PlainMonthDay.from('--11-30');" + "d.monthCode.substring(1) + ',' + d.day"));
        assertEquals(2020, num("Temporal.PlainMonthDay.from('2020-11-30').getISOFields().isoYear"));
        assertEquals("11,30", str(
                "var d = Temporal.PlainMonthDay.from({monthCode: 'M11', day: 30}); d.monthCode.substring(1) + ',' + d.day"));
        assertTrue(bool("var a = new Temporal.PlainMonthDay(11, 30); Temporal.PlainMonthDay.from(a).equals(a)"));
    }

    @Test
    public void test_string_forms_and_iso_fields() {
        assertEquals("11-30", str("new Temporal.PlainMonthDay(11, 30).toString()"));
        assertEquals("1972-11-30[u-ca=iso8601]",
                str("new Temporal.PlainMonthDay(11, 30).toString({calendarName: 'always'})"));
        assertEquals("11-30", str("new Temporal.PlainMonthDay(11, 30).toJSON()"));
        assertEquals("11-30", str("new Temporal.PlainMonthDay(11, 30).toLocaleString()"));
        assertEquals("iso8601,30,11,1972", str("var f = new Temporal.PlainMonthDay(11, 30).getISOFields();"
                + "f.calendar + ',' + f.isoDay + ',' + f.isoMonth + ',' + f.isoYear"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainMonthDay(11, 30).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.PlainMonthDay(11, 30)"));
    }

    @Test
    public void test_to_plain_date() {
        assertEquals("2020-11-30", str("new Temporal.PlainMonthDay(11, 30).toPlainDate({year: 2020}).toString()"));
        assertEquals("2019-02-28", str("new Temporal.PlainMonthDay(2, 29).toPlainDate({year: 2019}).toString()"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainMonthDay(11, 30).toPlainDate()"));
    }

    @Test
    public void test_constructor_non_finite_field_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainMonthDay(NaN, 1)"));
    }

    @Test
    public void test_from_rejects_non_convertible_and_missing_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainMonthDay.from(42)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainMonthDay.from({day: 30})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainMonthDay.from({month: 11})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainMonthDay.from({month: 6, monthCode: 'M11', day: 30})"));
    }

    @Test
    public void test_from_month_field_without_month_code() {
        assertEquals("11,30", str("var d = Temporal.PlainMonthDay.from({month: 11, day: 30});"
                + "d.monthCode.substring(1) + ',' + d.day"));
    }

    @Test
    public void test_invalid_month_codes_rejected() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainMonthDay.from({monthCode: 'X11', day: 30})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainMonthDay.from({monthCode: 'M13', day: 30})"));
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainMonthDay.from({monthCode: 'Mxx', day: 30})"));
    }

    @Test
    public void test_options_must_be_object() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainMonthDay(11, 30).with({day: 1}, 42)"));
    }

    @Test
    public void test_with_rejects_non_object() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainMonthDay(11, 30).with(42)"));
    }

    @Test
    public void test_with_explicit_overflow() {
        assertEquals("06,15",
                str("var d = new Temporal.PlainMonthDay(11, 30);"
                        + "var e = d.with({monthCode: 'M06', day: 15}, {overflow: 'reject'});"
                        + "e.monthCode.substring(1) + ',' + e.day"));
    }
}
