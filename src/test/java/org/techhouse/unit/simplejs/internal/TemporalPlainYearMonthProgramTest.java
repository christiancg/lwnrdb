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

public class TemporalPlainYearMonthProgramTest {
    private static double num() {
        return ((JsNumber) Interpreter.run("new Temporal.PlainYearMonth(2024, 3).month")).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_construction() {
        assertEquals("2024,3", str("var d = new Temporal.PlainYearMonth(2024, 3); d.year + ',' + d.month"));
    }

    @Test
    public void test_numeric_field_accessor() {
        assertEquals(3, num());
    }

    @Test
    public void test_field_accessors_agree_with_getISOFields() {
        assertTrue(bool("var d = new Temporal.PlainYearMonth(2024, 3); var f = d.getISOFields();"
                + "f.isoYear === d.year && f.isoMonth === d.month"));
    }

    @Test
    public void test_to_string() {
        assertEquals("2024-03", str("new Temporal.PlainYearMonth(2024, 3).toString()"));
    }

    @Test
    public void test_add_subtract_round_trip() {
        assertTrue(bool("var d = new Temporal.PlainYearMonth(2024, 3);"
                + "d.add({months: 7}).subtract({months: 7}).equals(d)"));
    }

    @Test
    public void test_until_since_are_signed_inverses() {
        assertTrue(bool("var a = new Temporal.PlainYearMonth(2024, 1); var b = new Temporal.PlainYearMonth(2024, 6);"
                + "a.until(b, {largestUnit: 'month'}).months === " + "-a.since(b, {largestUnit: 'month'}).months"));
    }

    @Test
    public void test_compare_agrees_with_equals() {
        assertTrue(bool("var a = new Temporal.PlainYearMonth(2024, 3); var b = new Temporal.PlainYearMonth(2024, 3);"
                + "Temporal.PlainYearMonth.compare(a, b) === 0 && a.equals(b)"));
    }

    // Round-tripping through toString()/from() preserves the value, including the reference day for
    // a full-date-string input
    @Test
    public void test_round_trips_through_string_form() {
        assertTrue(bool("var ym = new Temporal.PlainYearMonth(2024, 3);"
                + "Temporal.PlainYearMonth.from(ym.toString()).equals(ym)"));
        assertTrue(bool("var ym = Temporal.PlainYearMonth.from('2024-03-17');"
                + "Temporal.PlainYearMonth.from(ym.toString()).getISOFields().isoDay === 1"));
    }

    @Test
    public void test_number_coercion_throws() {
        final var result = new SimpleJs().run("return Number(new Temporal.PlainYearMonth(2024, 3));",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return new Temporal.PlainYearMonth(2024, 13);",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("RangeError", result.getErrorName());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return Temporal.PlainYearMonth(2024, 3);", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_successful_result_serializes_as_iso_string() {
        final var result = new SimpleJs().run("return new Temporal.PlainYearMonth(2024, 3);",
                SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("\"2024-03\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }
}
