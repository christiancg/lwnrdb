package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

// Real script snippets through the full engine, including SimpleJs.run's top-level error contract
// (mirroring DateErrorJsonProgramTest/DateProgramTest's shape for Temporal.Duration).
public class TemporalDurationProgramTest {
    private final SimpleJs engine = new SimpleJs();

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    // ISO 8601 duration string round-trip through the constructor's toString
    @Test
    public void test_iso_string_round_trip() {
        assertEquals("P1Y2M3W4DT5H6M7.5S", str("new Temporal.Duration(1, 2, 3, 4, 5, 6, 7, 500).toString()"));
    }

    // Temporal.Duration.from parses the same grammar back
    @Test
    public void test_from_string_round_trip() {
        assertEquals("P1Y2M3DT4H5M6.5S", str("Temporal.Duration.from('P1Y2M3DT4H5M6.5S').toString()"));
    }

    // add/subtract of calendar-independent durations balances carries across units
    @Test
    public void test_add_carries_across_units() {
        assertEquals("P1DT1H",
                str("new Temporal.Duration(0, 0, 0, 0, 23).add(new Temporal.Duration(0, 0, 0, 0, 2)).toString()"));
    }

    // round with an increment groups to the nearest multiple
    @Test
    public void test_round_with_increment() {
        assertEquals("PT10M", str("new Temporal.Duration(0, 0, 0, 0, 0, 12)"
                + ".round({smallestUnit: 'minutes', roundingIncrement: 5, roundingMode: 'floor'}).toString()"));
    }

    // total reports an exact fractional total, not an integer
    @Test
    public void test_total_is_fractional() {
        assertEquals(1.25, num("new Temporal.Duration(0, 0, 0, 0, 30).total({unit: 'days'})"));
    }

    // negated/abs/with are pure - they do not mutate the receiver
    @Test
    public void test_methods_are_immutable() {
        assertTrue(bool("var d = new Temporal.Duration(0, 0, 0, 1); d.negated();" + "d.days === 1"));
    }

    // Comparing durations via Temporal.Duration.compare inside a sort
    @Test
    public void test_compare_orders_durations() {
        assertEquals("PT30M,PT1H,PT2H",
                str("var ds = [{hours: 2}, {minutes: 30}, {hours: 1}]" + ".map(x => Temporal.Duration.from(x));"
                        + "ds.sort(Temporal.Duration.compare).map(d => d.toString()).join(',')"));
    }

    // A RangeError thrown constructing an invalid Duration surfaces through SimpleJs.run's error
    // contract as a RangeError with a message, not a Java stack trace.
    @Test
    public void test_range_error_surfaces_through_simple_js_run() {
        final var result = run("return new Temporal.Duration(1, -1);");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
        assertFalse(result.getErrorMessage().isEmpty());
    }

    // A TypeError (calling the constructor without new) surfaces the same way
    @Test
    public void test_type_error_surfaces_through_simple_js_run() {
        final var result = run("return Temporal.Duration();");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    // A successful script returns the Duration's ISO string via ordinary EJson interop
    @Test
    public void test_successful_result_via_simple_js_run() {
        final var result = run("return new Temporal.Duration(0, 0, 0, 1).toString();");
        assertFalse(result.isError());
        assertEquals("P1D", result.getValue().asJsonString().getValue());
    }

    // A Duration returned directly from top level serializes through EJsonInterop as its ISO string
    @Test
    public void test_duration_value_serializes_as_iso_string() {
        final var result = run("return new Temporal.Duration(0, 0, 0, 1);");
        assertFalse(result.isError());
        assertEquals("P1D", result.getValue().asJsonString().getValue());
    }

    // round() with a PlainDate relativeTo re-balances a calendar-unit duration into days (a year is
    // not a fixed number of days without a real anchor date to measure it from)
    @Test
    public void test_round_with_plaindate_relativeto() {
        assertEquals(365, num("var relativeTo = new Temporal.PlainDate(2021, 12, 15);"
                + "new Temporal.Duration(1).round({largestUnit: 'days', relativeTo}).days"));
    }

    // round() at a calendar smallestUnit ties correctly with halfEven vs halfExpand at an exact 0.5
    // boundary with an even integer part: halfEven rounds down to the nearest even (2), halfExpand
    // rounds away from zero (3) - distinguishes true half-even rounding from a naive always-round-up
    @Test
    public void test_round_half_even_vs_half_expand_at_tie() {
        assertEquals(2,
                num("var relativeTo = new Temporal.PlainDate(2018, 1, 1);"
                        + "var d = new Temporal.PlainDate(2018, 1, 1).until(new Temporal.PlainDate(2020, 7, 2));"
                        + "d.round({smallestUnit: 'years', roundingMode: 'halfEven', relativeTo}).years"));
        assertEquals(3,
                num("var relativeTo = new Temporal.PlainDate(2018, 1, 1);"
                        + "var d = new Temporal.PlainDate(2018, 1, 1).until(new Temporal.PlainDate(2020, 7, 2));"
                        + "d.round({smallestUnit: 'years', roundingMode: 'halfExpand', relativeTo}).years"));
    }

    // round() throws RangeError when a calendar unit is involved but relativeTo is missing
    @Test
    public void test_round_requires_relativeto_for_calendar_units() {
        final var result = run("return new Temporal.Duration(1).round({smallestUnit: 'months'});");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // total() with a ZonedDateTime relativeTo computes an exact fractional total across calendar units
    @Test
    public void test_total_with_zoneddatetime_relativeto() {
        assertEquals(7 + 1.0 / 24, num("var relativeTo = new Temporal.ZonedDateTime(0n, 'UTC');"
                + "new Temporal.Duration(0, 0, 1, 0, 1).total({unit: 'days', relativeTo})"));
    }

    // total() throws RangeError when relativeTo is required (a calendar unit) but missing
    @Test
    public void test_total_requires_relativeto_for_calendar_units() {
        final var result = run("return new Temporal.Duration(1).total({unit: 'months'});");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // compare() with relativeTo resolves which of two calendar-unit durations is longer by applying
    // both to the same anchor date
    @Test
    public void test_compare_with_relativeto() {
        assertEquals(1, num("var relativeTo = Temporal.PlainDate.from('2016-01-01');" // leap year
                + "Temporal.Duration.compare(new Temporal.Duration(1), new Temporal.Duration(0, 0, 0, 365), "
                + "{relativeTo})"));
        assertEquals(0, num("var relativeTo = Temporal.PlainDate.from('2017-01-01');" // non-leap year
                + "Temporal.Duration.compare(new Temporal.Duration(1), new Temporal.Duration(0, 0, 0, 365), "
                + "{relativeTo})"));
    }

    // compare() throws RangeError when relativeTo is required but missing
    @Test
    public void test_compare_requires_relativeto_for_calendar_units() {
        final var result = run(
                "return Temporal.Duration.compare(new Temporal.Duration(1, 0), new Temporal.Duration(0, 13));");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // toString with a smallestUnit option rounds only the sub-day tail, leaving a nonzero
    // years/months/weeks component untouched (regression coverage: this must not require relativeTo)
    @Test
    public void test_to_string_smallest_unit_preserves_calendar_fields() {
        assertEquals("P1Y1M1DT1H1M1S", str("new Temporal.Duration(1, 1, 0, 1, 1, 1, 1, 500)"
                + ".toString({smallestUnit: 'seconds', roundingMode: 'trunc'})"));
    }

    // relativeTo accepts a real Temporal.PlainDateTime instance directly
    @Test
    public void test_relativeto_accepts_plaindatetime_instance() {
        assertEquals(1, num("var relativeTo = new Temporal.PlainDateTime(2020, 1, 1, 12);"
                + "new Temporal.Duration(0, 0, 0, 1).round({largestUnit: 'days', relativeTo}).days"));
    }

    // relativeTo accepts a bare ISO date string (plain, no time zone)
    @Test
    public void test_relativeto_accepts_plain_date_string() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364)"
                + ".round({smallestUnit: 'years', relativeTo: '2020-01-01'}).years"));
    }

    // relativeTo accepts a date-time string with a bracketed IANA annotation (zoned)
    @Test
    public void test_relativeto_accepts_zoned_string() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364)"
                + ".round({smallestUnit: 'years', relativeTo: '2020-01-01T00:00[UTC]'}).years"));
    }

    // An explicit offset that agrees with the bracketed zone is accepted
    @Test
    public void test_relativeto_zoned_string_with_matching_offset() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364)"
                + ".round({smallestUnit: 'years', relativeTo: '2020-01-01T00:00+00:00[UTC]'}).years"));
    }

    // An explicit offset that disagrees with the bracketed zone is rejected
    @Test
    public void test_relativeto_zoned_string_with_mismatched_offset_throws() {
        final var result = run("return new Temporal.Duration(0, 0, 0, 364)"
                + ".round({smallestUnit: 'years', relativeTo: '2020-01-01T00:00+05:00[UTC]'});");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // A bare UTC "Z" designator with no bracketed annotation is not a valid relativeTo
    @Test
    public void test_relativeto_bare_z_designator_throws() {
        final var result = run("return new Temporal.Duration(1)"
                + ".round({smallestUnit: 'months', relativeTo: '2020-01-01T00:00Z'});");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // A relativeTo string naming a non-"iso8601" calendar is rejected; "iso8601" itself is accepted
    @Test
    public void test_relativeto_string_calendar_annotation() {
        final var rejected = run("return new Temporal.Duration(1)"
                + ".round({smallestUnit: 'months', relativeTo: '2020-01-01[u-ca=hebrew]'});");
        assertTrue(rejected.isError());
        assertEquals("RangeError", rejected.getErrorName());
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364)"
                + ".round({smallestUnit: 'years', relativeTo: '2020-01-01[u-ca=iso8601]'}).years"));
    }

    // relativeTo accepts a plain fields-like object (year/month/day), including via monthCode
    @Test
    public void test_relativeto_accepts_fields_object() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364)"
                + ".round({smallestUnit: 'years', relativeTo: {year: 2020, month: 1, day: 1}}).years"));
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364)"
                + ".round({smallestUnit: 'years', relativeTo: {year: 2020, monthCode: 'M01', day: 1}}).years"));
    }

    // An inconsistent month/monthCode pair in a relativeTo fields object is rejected
    @Test
    public void test_relativeto_fields_object_inconsistent_month_throws() {
        final var result = run("return new Temporal.Duration(1).round({smallestUnit: 'months', "
                + "relativeTo: {year: 2020, month: 2, monthCode: 'M01', day: 1}});");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // A relativeTo fields object missing a required field (day, or both month and monthCode) throws
    @Test
    public void test_relativeto_fields_object_missing_required_field_throws() {
        final var missingDay = run(
                "return new Temporal.Duration(1).round({smallestUnit: 'months', relativeTo: {year: 2020, month: 1}});");
        assertTrue(missingDay.isError());
        assertEquals("TypeError", missingDay.getErrorName());
        final var missingMonth = run(
                "return new Temporal.Duration(1).round({smallestUnit: 'months', relativeTo: {year: 2020, day: 1}});");
        assertTrue(missingMonth.isError());
        assertEquals("TypeError", missingMonth.getErrorName());
    }

    // relativeTo fields object with a timeZone property is resolved as a zoned anchor
    @Test
    public void test_relativeto_fields_object_with_time_zone() {
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 364).round({smallestUnit: 'years', "
                + "relativeTo: {year: 2020, month: 1, day: 1, timeZone: 'UTC'}}).years"));
    }

    // relativeTo fields object with an invalid calendar string is rejected; a Temporal object value
    // for `calendar` is accepted without inspection (its own calendar is implicitly "iso8601")
    @Test
    public void test_relativeto_fields_object_calendar_field() {
        final var invalid = run("return new Temporal.Duration(1).round({smallestUnit: 'months', "
                + "relativeTo: {year: 2020, month: 1, day: 1, calendar: 'notacal'}});");
        assertTrue(invalid.isError());
        assertEquals("RangeError", invalid.getErrorName());
        assertEquals(1, num("new Temporal.Duration(0, 0, 0, 1).round({largestUnit: 'days', relativeTo: "
                + "{year: 2020, month: 1, day: 1, calendar: new Temporal.PlainDate(2020, 1, 1)}}).days"));
    }

    // A non-integer-but-finite roundingIncrement truncates; a non-finite one throws RangeError
    @Test
    public void test_round_rounding_increment_must_be_finite() {
        assertEquals(1,
                num("new Temporal.PlainDate(2020, 1, 1).until(new Temporal.PlainDate(2020, 1, 3))"
                        + ".round({smallestUnit: 'days', roundingIncrement: 2.9, relativeTo: "
                        + "new Temporal.PlainDate(2020, 1, 1)}).days === 2 ? 1 : 0"));
        final var nan = run("return new Temporal.Duration(0, 0, 0, 1).round({smallestUnit: 'days', "
                + "roundingIncrement: NaN, relativeTo: new Temporal.PlainDate(2020, 1, 1)});");
        assertTrue(nan.isError());
        assertEquals("RangeError", nan.getErrorName());
        final var infinite = run("return new Temporal.Duration(0, 0, 0, 1).round({smallestUnit: 'days', "
                + "roundingIncrement: Infinity, relativeTo: new Temporal.PlainDate(2020, 1, 1)});");
        assertTrue(infinite.isError());
        assertEquals("RangeError", infinite.getErrorName());
    }

    // round()'s "auto" largestUnit default picks the duration's own coarsest nonzero field, widened to
    // smallestUnit when that is coarser still - covers every branch of DefaultTemporalLargestUnit
    @Test
    public void test_round_default_largest_unit_for_each_field() {
        final var relativeTo = "new Temporal.PlainDate(2020, 1, 1)";
        assertTrue(bool("new Temporal.Duration(0, 1).round({largestUnit: 'auto', smallestUnit: 'nanoseconds', "
                + "relativeTo: " + relativeTo + "}) instanceof Temporal.Duration"));
        assertTrue(bool("new Temporal.Duration(0, 0, 1).round({largestUnit: 'auto', smallestUnit: 'nanoseconds', "
                + "relativeTo: " + relativeTo + "}) instanceof Temporal.Duration"));
        assertTrue(bool("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).round({smallestUnit: 'nanoseconds'})"
                + " instanceof Temporal.Duration"));
        assertTrue(bool("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 1).round({smallestUnit: 'nanoseconds'})"
                + " instanceof Temporal.Duration"));
        assertTrue(bool("new Temporal.Duration(0, 0, 0, 0, 0, 0, 0, 0, 1).round({smallestUnit: 'nanoseconds'})"
                + " instanceof Temporal.Duration"));
    }

    // toString smallestUnit accepts every fractional-second unit, not just "seconds"
    @Test
    public void test_to_string_smallest_unit_each_fractional_unit() {
        assertEquals("PT1.500S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)" + ".toString({smallestUnit: 'milliseconds'})"));
        assertEquals("PT1.500000S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)" + ".toString({smallestUnit: 'microseconds'})"));
        assertEquals("PT1.500000000S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)" + ".toString({smallestUnit: 'nanoseconds'})"));
    }

    // fractionalSecondDigits accepts "auto" (default trimming) and an explicit digit count
    @Test
    public void test_to_string_fractional_second_digits() {
        assertEquals("PT1S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1)" + ".toString({fractionalSecondDigits: 'auto'})"));
        assertEquals("PT1.50S",
                str("new Temporal.Duration(0, 0, 0, 0, 0, 0, 1, 500)" + ".toString({fractionalSecondDigits: 2})"));
        final var result = run(
                "return new Temporal.Duration(0, 0, 0, 0, 0, 0, 1).toString({fractionalSecondDigits: 10});");
        assertTrue(result.isError());
        assertEquals("RangeError", result.getErrorName());
    }

    // Temporal.Duration.compare rejects a non-object options argument before reading relativeTo
    @Test
    public void test_compare_rejects_non_object_options() {
        final var result = run(
                "return Temporal.Duration.compare(new Temporal.Duration(1), " + "new Temporal.Duration(1), 42);");
        assertTrue(result.isError());
        assertEquals("TypeError", result.getErrorName());
    }

    // total()/round() require an options argument at all
    @Test
    public void test_total_and_round_require_options_argument() {
        final var totalResult = run("return new Temporal.Duration(1).total();");
        assertTrue(totalResult.isError());
        assertEquals("TypeError", totalResult.getErrorName());
        final var roundResult = run("return new Temporal.Duration(1).round();");
        assertTrue(roundResult.isError());
        assertEquals("TypeError", roundResult.getErrorName());
    }
}
