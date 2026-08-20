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

public class TemporalZonedDateTimeProgramTest {
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
        assertEquals("2024,3,10,9,15,30,UTC",
                str("var z = new Temporal.ZonedDateTime("
                        + "Temporal.Instant.from('2024-03-10T09:15:30Z').epochNanoseconds, 'UTC');"
                        + "z.year + ',' + z.month + ',' + z.day + ',' + z.hour + ',' + z.minute + ',' + z.second + ','"
                        + "+ z.timeZoneId"));
    }

    // The offset/hoursInDay accessors reflect the target time zone, not just UTC
    @Test
    public void test_offset_and_hours_in_day_accessors() {
        assertEquals("2020-06-15T10:00:00-04:00[America/New_York]",
                str("Temporal.ZonedDateTime.from('2020-06-15T10:00:00-04:00[America/New_York]').toString()"));
        assertEquals(24, num("Temporal.ZonedDateTime.from('2020-06-15T00:00:00-04:00[America/New_York]').hoursInDay"));
        // 2020-03-08 is a spring-forward day in America/New_York: only 23 real hours long.
        assertEquals(23, num("Temporal.ZonedDateTime.from('2020-03-08T00:00:00-05:00[America/New_York]').hoursInDay"));
    }

    // toInstant/toPlainDate/toPlainTime/toPlainDateTime split the value into its component types
    @Test
    public void test_conversions_split_into_component_types() {
        assertTrue(bool("var z = new Temporal.ZonedDateTime("
                + "Temporal.Instant.from('2024-03-10T09:15:30Z').epochNanoseconds, 'UTC');"
                + "z.toInstant().equals(Temporal.Instant.from('2024-03-10T09:15:30Z')) && "
                + "z.toPlainDate().equals(new Temporal.PlainDate(2024, 3, 10)) && "
                + "z.toPlainTime().equals(new Temporal.PlainTime(9, 15, 30)) && "
                + "z.toPlainDateTime().equals(new Temporal.PlainDateTime(2024, 3, 10, 9, 15, 30))"));
    }

    // add() across a DST spring-forward boundary in America/New_York: adding a calendar day keeps the
    // same wall-clock time (the zone absorbs the gap), while adding 24 exact hours lands an hour later
    @Test
    public void test_add_across_dst_boundary() {
        assertEquals("0,1",
                str("var z = Temporal.ZonedDateTime.from('2020-03-08T00:00:00-05:00[America/New_York]');"
                        + "var byDay = z.add({days: 1}); var byHours = z.add({hours: 24});"
                        + "byDay.hour + ',' + byHours.hour"));
    }

    // subtract() is add()'s inverse for a pure exact-time (hours-only) duration
    @Test
    public void test_subtract_is_add_inverse_for_exact_time() {
        assertTrue(bool("var z = Temporal.ZonedDateTime.from('2020-06-15T10:00:00-04:00[America/New_York]');"
                + "z.add({hours: 5}).subtract({hours: 5}).equals(z)"));
    }

    // compare() agrees with equals() on ordering, independent of the time zone used to display them
    @Test
    public void test_compare_agrees_with_equals() {
        assertTrue(bool(
                "var a = new Temporal.ZonedDateTime(0n, 'UTC');" + "var b = new Temporal.ZonedDateTime(0n, '+02:00');"
                        + "Temporal.ZonedDateTime.compare(a, b) === 0 && a.equals(b) === false"));
    }

    // getTimeZoneTransition finds the next real DST transition and it is strictly later
    @Test
    public void test_get_time_zone_transition_finds_next_dst_change() {
        assertTrue(bool("var z = new Temporal.ZonedDateTime(0n, 'America/New_York');"
                + "var next = z.getTimeZoneTransition('next');" + "next instanceof Temporal.ZonedDateTime && "
                + "Temporal.ZonedDateTime.compare(next, z) > 0"));
    }

    // A zone with no transitions (UTC) has no next/previous transition
    @Test
    public void test_get_time_zone_transition_null_for_utc() {
        assertTrue(bool("new Temporal.ZonedDateTime(0n, 'UTC').getTimeZoneTransition('next') === null"));
    }

    // A RangeError thrown from the constructor surfaces through SimpleJs.run's error contract
    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return new Temporal.ZonedDateTime(0n, 'Not/AZone');",
                SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("RangeError", result.getErrorName());
        assertTrue(result.getErrorMessage() != null && !result.getErrorMessage().isEmpty());
    }

    // A TypeError thrown from calling the constructor without `new` surfaces the same way
    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = new SimpleJs().run("return Temporal.ZonedDateTime(0n, 'UTC');", SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        assertEquals("TypeError", result.getErrorName());
    }

    // A successful script round-trips a Temporal.ZonedDateTime value as its canonical string via EJson
    @Test
    public void test_successful_result_serializes_as_iso_string() {
        final var result = new SimpleJs().run("return new Temporal.ZonedDateTime(0n, 'UTC');",
                SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        assertEquals("\"1970-01-01T00:00:00+00:00[UTC]\"", new org.techhouse.ejson.EJson().toJson(result.getValue()));
    }
}
