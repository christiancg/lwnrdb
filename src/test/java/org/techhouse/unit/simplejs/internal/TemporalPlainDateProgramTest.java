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

    // A basic construction round-trips through its own field accessors
    @Test
    public void test_construction() {
        assertEquals("2024,3,10",
                str("var d = new Temporal.PlainDate(2024, 3, 10);" + "d.year + ',' + d.month + ',' + d.day"));
    }

    // Individual numeric field accessors round-trip through the engine
    @Test
    public void test_numeric_field_accessor() {
        assertEquals(10, num("new Temporal.PlainDate(2024, 3, 10).day"));
    }

    // getISOFields and the field accessors agree with each other
    @Test
    public void test_field_accessors_agree_with_getISOFields() {
        assertTrue(bool("var d = new Temporal.PlainDate(2024, 3, 10); var f = d.getISOFields();"
                + "f.isoYear === d.year && f.isoMonth === d.month && f.isoDay === d.day"));
    }

    // dayOfWeek/weekOfYear/inLeapYear read together for a single date (2024-01-01 is a Monday, so
    // 2024-01-15 - exactly two weeks later - is a Monday too, in ISO week 3 of 2024)
    @Test
    public void test_calendar_derived_fields() {
        assertEquals("1,3,2024,true", str("var d = new Temporal.PlainDate(2024, 1, 15);"
                + "d.dayOfWeek + ',' + d.weekOfYear + ',' + d.yearOfWeek + ',' + d.inLeapYear"));
    }

    // toString renders the canonical ISO date form
    @Test
    public void test_to_string() {
        assertEquals("2024-03-10", str("new Temporal.PlainDate(2024, 3, 10).toString()"));
    }

    // add/subtract are inverses of each other for a same-shaped duration
    @Test
    public void test_add_subtract_round_trip() {
        assertTrue(bool(
                "var d = new Temporal.PlainDate(2024, 3, 10);" + "d.add({days: 40}).subtract({days: 40}).equals(d)"));
    }

    // until/since agree in magnitude but disagree in sign
    @Test
    public void test_until_since_are_signed_inverses() {
        assertTrue(bool("var a = new Temporal.PlainDate(2024, 1, 1); var b = new Temporal.PlainDate(2024, 3, 1);"
                + "a.until(b).days === -a.since(b).days"));
    }

    // compare() agrees with equals()/until() on ordering
    @Test
    public void test_compare_agrees_with_equals() {
        assertTrue(bool("var a = new Temporal.PlainDate(2024, 3, 10); var b = new Temporal.PlainDate(2024, 3, 10);"
                + "Temporal.PlainDate.compare(a, b) === 0 && a.equals(b)"));
    }

    // Number(plainDate) throws through the ops-aware ToNumber coercion path
    @Test
    public void test_number_coercion_throws() {
        final var result = new SimpleJs().run("return Number(new Temporal.PlainDate(2024, 3, 10));",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    // A RangeError thrown from the constructor surfaces through SimpleJs.run's error contract
    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return new Temporal.PlainDate(2024, 2, 30);",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("RangeError", result.getErrorName());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    // A TypeError thrown from calling the constructor without `new` surfaces the same way
    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return Temporal.PlainDate(2024, 2, 1);", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    // A successful script still round-trips a Temporal.PlainDate value as its ISO string via EJson
    @Test
    public void test_successful_result_serializes_as_iso_string() {
        final var result = new SimpleJs().run("return new Temporal.PlainDate(2024, 3, 10);",
                SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("\"2024-03-10\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }

    // until() rounds to a calendar smallestUnit finer than its largestUnit default, using the receiver
    // as the implicit anchor - halfExpand rounds a half-year-ish remainder up
    @Test
    public void test_until_rounds_to_years() {
        assertEquals(2,
                num("var a = new Temporal.PlainDate(2019, 1, 1);" + "var b = new Temporal.PlainDate(2020, 7, 2);"
                        + "a.until(b, {smallestUnit: 'years', roundingMode: 'halfExpand'}).years"));
    }
}
