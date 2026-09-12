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

public class TemporalPlainDateTimeProgramTest {
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
        assertEquals("2024,3,10,9,15,30", str("var d = new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30);"
                + "d.year + ',' + d.month + ',' + d.day + ',' + d.hour + ',' + d.minute + ',' + d.second"));
    }

    @Test
    public void test_numeric_field_accessor() {
        assertEquals(10, num("new Temporal.PlainDateTime(2024, 3, 10, 9).day"));
        assertEquals(9, num("new Temporal.PlainDateTime(2024, 3, 10, 9).hour"));
    }

    @Test
    public void test_field_accessors_agree_with_getISOFields() {
        assertTrue(bool("var d = new Temporal.PlainDateTime(2024, 3, 10, 9, 15); var f = d.getISOFields();"
                + "f.isoYear === d.year && f.isoMonth === d.month && f.isoDay === d.day && f.isoHour === d.hour"
                + " && f.isoMinute === d.minute"));
    }

    @Test
    public void test_to_string() {
        assertEquals("2024-03-10T09:15:30", str("new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30).toString()"));
    }

    @Test
    public void test_add_subtract_round_trip() {
        assertTrue(bool("var d = new Temporal.PlainDateTime(2024, 3, 10, 23, 30);"
                + "d.add({hours: 5}).subtract({hours: 5}).equals(d)"));
    }

    @Test
    public void test_until_since_are_signed_inverses() {
        assertTrue(bool("var a = new Temporal.PlainDateTime(2024, 1, 1, 10); "
                + "var b = new Temporal.PlainDateTime(2024, 1, 3, 4);"
                + "a.until(b).total({unit: 'hour'}) === -a.since(b).total({unit: 'hour'})"));
    }

    @Test
    public void test_to_plain_date_and_time_split() {
        assertTrue(bool("var d = new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30);"
                + "d.toPlainDate().equals(new Temporal.PlainDate(2024, 3, 10)) && "
                + "d.toPlainTime().equals(new Temporal.PlainTime(9, 15, 30))"));
    }

    @Test
    public void test_compare_agrees_with_equals() {
        assertTrue(bool("var a = new Temporal.PlainDateTime(2024, 3, 10, 1); "
                + "var b = new Temporal.PlainDateTime(2024, 3, 10, 1);"
                + "Temporal.PlainDateTime.compare(a, b) === 0 && a.equals(b)"));
    }

    @Test
    public void test_number_coercion_throws() {
        final var result = new SimpleJs().run("return Number(new Temporal.PlainDateTime(2024, 3, 10));",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return new Temporal.PlainDateTime(2024, 2, 30);",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("RangeError", result.getErrorName());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return Temporal.PlainDateTime(2024, 2, 1);", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    @Test
    public void test_successful_result_serializes_as_iso_string() {
        final var result = new SimpleJs().run("return new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30);",
                SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("\"2024-03-10T09:15:30\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }

    @Test
    public void test_until_with_years_largest_unit() {
        assertEquals("3,6,17",
                str("var a = new Temporal.PlainDateTime(1997, 12, 1, 12);"
                        + "var b = new Temporal.PlainDateTime(2001, 6, 18, 12);"
                        + "var d = a.until(b, {largestUnit: 'years'}); d.years + ',' + d.months + ',' + d.days"));
    }

    @Test
    public void test_until_rounds_up_to_years() {
        assertEquals(2,
                num("var a = new Temporal.PlainDateTime(2019, 1, 1);"
                        + "var b = new Temporal.PlainDateTime(2020, 7, 2);"
                        + "a.until(b, {smallestUnit: 'years', roundingMode: 'halfExpand'}).years"));
    }

    @Test
    public void test_until_rounds_months_carries_into_years() {
        assertEquals("2,0",
                str("var a = new Temporal.PlainDateTime(2022, 1, 1);"
                        + "var b = new Temporal.PlainDateTime(2023, 12, 25);"
                        + "var d = a.until(b, {largestUnit: 'years', smallestUnit: 'months', roundingMode: 'expand'});"
                        + "d.years + ',' + d.months"));
    }

    // Regression coverage for the differenceCalendar -> weeks refinement: weeks stays 0, not a fractional-week
    // artifact.
    @Test
    public void test_until_exact_month_multiple_at_weeks_granularity() {
        assertEquals("1,0,0",
                str("var a = new Temporal.PlainDateTime(2012, 1, 1, 12);"
                        + "var b = new Temporal.PlainDateTime(2012, 2, 1, 12);"
                        + "var d = a.until(b, {smallestUnit: 'weeks', largestUnit: 'months'});"
                        + "d.months + ',' + d.weeks + ',' + d.days"));
    }
}
