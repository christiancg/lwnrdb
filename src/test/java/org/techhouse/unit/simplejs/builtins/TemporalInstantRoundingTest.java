package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

/**
 * Temporal.Instant rounds two different ways, and which one applies is not a detail: `round`/`toString` round
 * the raw epoch value as if it were positive (so `trunc` is `floor` below the epoch), while `until`/`since`
 * produce a signed Duration and round its magnitude (so `trunc` shrinks it in both directions).
 */
public class TemporalInstantRoundingTest {
    private static final String MODES = "['ceil','floor','trunc','expand','halfCeil','halfFloor',"
            + "'halfExpand','halfTrunc','halfEven']";

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String attempt(String expression) {
        return str("(() => { try { return String(" + expression + "); } catch (e) { return e.constructor.name; } })()");
    }

    // Above the epoch the two conventions agree
    @Test
    public void test_rounding_an_instant_after_the_epoch() {
        assertEquals("06,05,05,06,06,05,06,05,06", str("""
                const instant = Temporal.Instant.from('2026-01-02T03:04:05.5Z');
                %s.map(mode => instant.round({ smallestUnit: 'second', roundingMode: mode })
                        .toString().slice(17, 19)).join(',')
                """.formatted(MODES)));
    }

    // Below the epoch every mode resolves against the floor/ceiling bracket, never the magnitude
    @Test
    public void test_rounding_an_instant_before_the_epoch_rounds_as_if_positive() {
        assertEquals("55,54,54,55,55,54,55,54,54", str("""
                const instant = Temporal.Instant.from('1969-12-31T23:59:54.5Z');
                %s.map(mode => instant.round({ smallestUnit: 'second', roundingMode: mode })
                        .toString().slice(17, 19)).join(',')
                """.formatted(MODES)));
    }

    @Test
    public void test_a_half_even_tie_settles_on_the_even_second() {
        assertEquals("2026-01-02T03:04:04Z", str("""
                Temporal.Instant.from('2026-01-02T03:04:04.5Z')
                    .round({ smallestUnit: 'second', roundingMode: 'halfEven' }).toString()
                """));
    }

    @Test
    public void test_a_rounding_increment_groups_the_unit() {
        assertEquals("2026-01-02T03:05:00Z", str("""
                Temporal.Instant.from('2026-01-02T03:04:05.5Z')
                    .round({ smallestUnit: 'minute', roundingIncrement: 5, roundingMode: 'ceil' }).toString()
                """));
    }

    @Test
    public void test_a_bare_unit_string_is_accepted() {
        assertEquals("2026-01-02T03:00:00Z",
                str("Temporal.Instant.from('2026-01-02T03:04:05.5Z').round('hour')" + ".toString()"));
    }

    // A positive difference: trunc shrinks it, expand grows it
    @Test
    public void test_a_positive_difference_rounds_by_magnitude() {
        assertEquals("PT3S,PT2S,PT2S,PT3S,PT3S,PT2S,PT3S,PT2S,PT2S", str("""
                const from = Temporal.Instant.from('2026-01-02T03:04:05Z');
                const to = Temporal.Instant.from('2026-01-02T03:04:07.5Z');
                %s.map(mode => from.until(to, { smallestUnit: 'second', roundingMode: mode }).toString()).join(',')
                """.formatted(MODES)));
    }

    // A negative difference: trunc still shrinks it, so it moves the other way than it did for `round`
    @Test
    public void test_a_negative_difference_rounds_by_magnitude() {
        assertEquals("-PT2S,-PT3S,-PT2S,-PT3S,-PT2S,-PT3S,-PT3S,-PT2S,-PT2S", str("""
                const from = Temporal.Instant.from('2026-01-02T03:04:05Z');
                const to = Temporal.Instant.from('2026-01-02T03:04:02.5Z');
                %s.map(mode => from.until(to, { smallestUnit: 'second', roundingMode: mode }).toString()).join(',')
                """.formatted(MODES)));
    }

    @Test
    public void test_a_half_even_difference_settles_on_the_even_second() {
        assertEquals("PT4S", str("""
                Temporal.Instant.from('2026-01-02T03:04:05Z')
                    .until(Temporal.Instant.from('2026-01-02T03:04:09.5Z'),
                        { smallestUnit: 'second', roundingMode: 'halfEven' }).toString()
                """));
    }

    @Test
    public void test_an_exact_difference_is_left_alone() {
        assertEquals("PT2S", str("""
                Temporal.Instant.from('2026-01-02T03:04:05Z')
                    .until(Temporal.Instant.from('2026-01-02T03:04:07Z'),
                        { smallestUnit: 'second', roundingMode: 'expand' }).toString()
                """));
    }

    @Test
    public void test_since_is_until_the_other_way_round() {
        assertEquals("PT2S", str("""
                Temporal.Instant.from('2026-01-02T03:04:07.5Z')
                    .since(Temporal.Instant.from('2026-01-02T03:04:05Z'),
                        { smallestUnit: 'second', roundingMode: 'floor' }).toString()
                """));
    }

    // An increment has to divide its unit evenly, or the grouping would not tile the timeline
    @Test
    public void test_an_increment_that_does_not_divide_its_unit_is_refused() {
        assertEquals("RangeError", attempt("Temporal.Instant.from('2026-01-02T03:04:05Z')"
                + ".round({ smallestUnit: 'second', roundingIncrement: 7 })"));
    }

    // An instant has no calendar, so no unit above an hour is meaningful for it
    @Test
    public void test_a_calendar_unit_is_refused() {
        assertEquals("RangeError",
                attempt("Temporal.Instant.from('2026-01-02T03:04:05Z').round({ smallestUnit: 'day' })"));
        assertEquals("RangeError",
                attempt("Temporal.Instant.from('2026-01-02T03:04:05Z').round({ smallestUnit: 'month' })"));
    }

    @Test
    public void test_a_calendar_unit_is_refused_for_a_difference_too() {
        assertEquals("RangeError", attempt("""
                Temporal.Instant.from('2026-01-02T03:04:05Z')
                    .until(Temporal.Instant.from('2026-01-03T00:00:00Z'), { smallestUnit: 'day' })
                """));
    }

    @Test
    public void test_a_zoned_date_time_converts_to_its_instant() {
        assertEquals("2026-01-02T03:04:05Z",
                str("Temporal.Instant.from(Temporal.ZonedDateTime.from('2026-01-02T03:04:05[UTC]')).toString()"));
    }

    @Test
    public void test_a_number_is_not_an_instant() {
        assertEquals("TypeError", attempt("Temporal.Instant.from(42)"));
    }

    @Test
    public void test_to_string_rounds_and_clips_the_fraction() {
        assertEquals("2026-01-02T03:05Z", str("""
                Temporal.Instant.from('2026-01-02T03:04:05Z')
                    .toString({ smallestUnit: 'minute', roundingMode: 'ceil' })
                """));
        assertEquals("2026-01-02T03:04:05.123Z", str("""
                Temporal.Instant.from('2026-01-02T03:04:05.123456789Z').toString({ fractionalSecondDigits: 3 })
                """));
    }
}
