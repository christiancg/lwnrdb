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

public class TemporalPlainDateProgramTest {
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
    public void test_construction() {
        assertEquals("2024,3,10",
                str("var d = new Temporal.PlainDate(2024, 3, 10);" + "d.year + ',' + d.month + ',' + d.day"));
    }

    @Test
    public void test_numeric_field_accessor() {
        assertEquals(10, num("new Temporal.PlainDate(2024, 3, 10).day"));
    }

    @Test
    public void test_field_accessors_agree_with_getISOFields() {
        assertTrue(bool("var d = new Temporal.PlainDate(2024, 3, 10); var f = d.getISOFields();"
                + "f.isoYear === d.year && f.isoMonth === d.month && f.isoDay === d.day"));
    }

    // 2024-01-01 is a Monday, so 2024-01-15 - exactly two weeks later - is a Monday too, in ISO week 3.
    @Test
    public void test_calendar_derived_fields() {
        assertEquals("1,3,2024,true", str("var d = new Temporal.PlainDate(2024, 1, 15);"
                + "d.dayOfWeek + ',' + d.weekOfYear + ',' + d.yearOfWeek + ',' + d.inLeapYear"));
    }

    @Test
    public void test_to_string() {
        assertEquals("2024-03-10", str("new Temporal.PlainDate(2024, 3, 10).toString()"));
    }

    @Test
    public void test_add_subtract_round_trip() {
        assertTrue(bool(
                "var d = new Temporal.PlainDate(2024, 3, 10);" + "d.add({days: 40}).subtract({days: 40}).equals(d)"));
    }

    @Test
    public void test_until_since_are_signed_inverses() {
        assertTrue(bool("var a = new Temporal.PlainDate(2024, 1, 1); var b = new Temporal.PlainDate(2024, 3, 1);"
                + "a.until(b).days === -a.since(b).days"));
    }

    @Test
    public void test_compare_agrees_with_equals() {
        assertTrue(bool("var a = new Temporal.PlainDate(2024, 3, 10); var b = new Temporal.PlainDate(2024, 3, 10);"
                + "Temporal.PlainDate.compare(a, b) === 0 && a.equals(b)"));
    }

    @Test
    public void test_number_coercion_throws() {
        final var result = new SimpleJs().run("return Number(new Temporal.PlainDate(2024, 3, 10));",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return new Temporal.PlainDate(2024, 2, 30);",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("RangeError", result.getErrorName());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return Temporal.PlainDate(2024, 2, 1);", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_successful_result_serializes_as_iso_string() {
        final var result = new SimpleJs().run("return new Temporal.PlainDate(2024, 3, 10);",
                SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("\"2024-03-10\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }

    @Test
    public void test_until_rounds_to_years() {
        assertEquals(2,
                num("var a = new Temporal.PlainDate(2019, 1, 1);" + "var b = new Temporal.PlainDate(2020, 7, 2);"
                        + "a.until(b, {smallestUnit: 'years', roundingMode: 'halfExpand'}).years"));
    }
}
