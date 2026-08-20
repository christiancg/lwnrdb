package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalPlainMonthDayProgramTest {
    private static double num() {
        return ((JsNumber) Interpreter.run("new Temporal.PlainMonthDay(3, 10).day")).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_construction() {
        assertEquals("M03,10", str("var d = new Temporal.PlainMonthDay(3, 10); d.monthCode + ',' + d.day"));
    }

    @Test
    public void test_numeric_field_accessor() {
        assertEquals(10, num());
    }

    @Test
    public void test_field_accessors_agree_with_getISOFields() {
        assertTrue(bool("var d = new Temporal.PlainMonthDay(3, 10); var f = d.getISOFields();"
                + "f.isoDay === d.day && f.isoMonth === 3"));
    }

    @Test
    public void test_to_string() {
        assertEquals("03-10", str("new Temporal.PlainMonthDay(3, 10).toString()"));
    }

    @Test
    public void test_with_round_trip() {
        assertTrue(bool("var d = new Temporal.PlainMonthDay(3, 10);" + "d.with({day: 20}).with({day: 10}).equals(d)"));
    }

    @Test
    public void test_equals_agrees_with_string_form() {
        assertTrue(bool("var a = new Temporal.PlainMonthDay(3, 10); var b = new Temporal.PlainMonthDay(3, 10);"
                + "a.equals(b) && a.equals(b.toString())"));
    }

    // Round-tripping through toString()/from() preserves the value, including the reference year for
    // a full-date-string input
    @Test
    public void test_round_trips_through_string_form() {
        assertTrue(bool("var md = new Temporal.PlainMonthDay(3, 10);"
                + "Temporal.PlainMonthDay.from(md.toString()).equals(md)"));
        assertTrue(bool("var md = Temporal.PlainMonthDay.from('2020-03-10');"
                + "Temporal.PlainMonthDay.from(md.toString({calendarName: 'always'}))"
                + ".getISOFields().isoYear === 2020"));
    }

    @Test
    public void test_number_coercion_throws() {
        final var result = new SimpleJs().run("return Number(new Temporal.PlainMonthDay(3, 10));",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return new Temporal.PlainMonthDay(2, 30);", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("RangeError", result.getErrorName());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return Temporal.PlainMonthDay(3, 10);", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_successful_result_serializes_as_iso_string() {
        final var result = new SimpleJs().run("return new Temporal.PlainMonthDay(3, 10);", SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("\"03-10\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }
}
