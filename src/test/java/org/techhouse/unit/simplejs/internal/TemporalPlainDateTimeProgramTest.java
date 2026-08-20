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

    // A basic construction round-trips through its own field accessors
    @Test
    public void test_construction() {
        assertEquals("2024,3,10,9,15,30", str("var d = new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30);"
                + "d.year + ',' + d.month + ',' + d.day + ',' + d.hour + ',' + d.minute + ',' + d.second"));
    }

    // Individual numeric field accessors round-trip through the engine
    @Test
    public void test_numeric_field_accessor() {
        assertEquals(10, num("new Temporal.PlainDateTime(2024, 3, 10, 9).day"));
        assertEquals(9, num("new Temporal.PlainDateTime(2024, 3, 10, 9).hour"));
    }

    // getISOFields and the field accessors agree with each other
    @Test
    public void test_field_accessors_agree_with_getISOFields() {
        assertTrue(bool("var d = new Temporal.PlainDateTime(2024, 3, 10, 9, 15); var f = d.getISOFields();"
                + "f.isoYear === d.year && f.isoMonth === d.month && f.isoDay === d.day && f.isoHour === d.hour"
                + " && f.isoMinute === d.minute"));
    }

    // toString renders the canonical ISO date-time form
    @Test
    public void test_to_string() {
        assertEquals("2024-03-10T09:15:30", str("new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30).toString()"));
    }

    // add/subtract are inverses of each other for a same-shaped duration, including a carry across a
    // day boundary
    @Test
    public void test_add_subtract_round_trip() {
        assertTrue(bool("var d = new Temporal.PlainDateTime(2024, 3, 10, 23, 30);"
                + "d.add({hours: 5}).subtract({hours: 5}).equals(d)"));
    }

    // until/since agree in magnitude but disagree in sign, and produce real Temporal.Duration values
    @Test
    public void test_until_since_are_signed_inverses() {
        assertTrue(bool("var a = new Temporal.PlainDateTime(2024, 1, 1, 10); "
                + "var b = new Temporal.PlainDateTime(2024, 1, 3, 4);"
                + "a.until(b).total({unit: 'hour'}) === -a.since(b).total({unit: 'hour'})"));
    }

    // toPlainDate/toPlainTime split the value into its two real Temporal component types
    @Test
    public void test_to_plain_date_and_time_split() {
        assertTrue(bool("var d = new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30);"
                + "d.toPlainDate().equals(new Temporal.PlainDate(2024, 3, 10)) && "
                + "d.toPlainTime().equals(new Temporal.PlainTime(9, 15, 30))"));
    }

    // compare() agrees with equals() on ordering
    @Test
    public void test_compare_agrees_with_equals() {
        assertTrue(bool("var a = new Temporal.PlainDateTime(2024, 3, 10, 1); "
                + "var b = new Temporal.PlainDateTime(2024, 3, 10, 1);"
                + "Temporal.PlainDateTime.compare(a, b) === 0 && a.equals(b)"));
    }

    // Number(plainDateTime) throws through the ops-aware ToNumber coercion path
    @Test
    public void test_number_coercion_throws() {
        final var result = new SimpleJs().run("return Number(new Temporal.PlainDateTime(2024, 3, 10));",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    // A RangeError thrown from the constructor surfaces through SimpleJs.run's error contract
    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return new Temporal.PlainDateTime(2024, 2, 30);",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("RangeError", result.getErrorName());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    // A TypeError thrown from calling the constructor without `new` surfaces the same way
    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return Temporal.PlainDateTime(2024, 2, 1);", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    // A successful script still round-trips a Temporal.PlainDateTime value as its ISO string via EJson
    @Test
    public void test_successful_result_serializes_as_iso_string() {
        final var result = new SimpleJs().run("return new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30);",
                SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("\"2024-03-10T09:15:30\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }
}
