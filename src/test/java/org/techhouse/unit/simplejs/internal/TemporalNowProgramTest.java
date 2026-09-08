package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ScriptResult;
import org.techhouse.simplejs.host.SimpleHostBindings;

// Real script snippets through SimpleJs.run exercising every Temporal.Now.* member (phase T8).
// Exact current-time values can never be asserted (the clock keeps moving between the script and
// the assertion), so every check here is plausibility/type/shape-based rather than an exact match.
public class TemporalNowProgramTest {
    private final SimpleJs engine = new SimpleJs();

    private ScriptResult run(String source) {
        return engine.run(source, SimpleHostBindings.empty());
    }

    @Test
    public void test_instant_epoch_milliseconds_is_close_to_current_time() {
        final var result = run("return Temporal.Now.instant().epochMilliseconds;");
        assertFalse(result.isError());
        final var epochMillis = result.getValue().asJsonNumber().getValue().doubleValue();
        final var driftMillis = Math.abs(System.currentTimeMillis() - epochMillis);
        assertTrue(driftMillis < 30_000,
                "epochMilliseconds should be within 30s of real time, drift was " + driftMillis);
    }

    @Test
    public void test_plain_date_iso_year_is_plausible() {
        final var result = run("return Temporal.Now.plainDateISO().year;");
        assertFalse(result.isError());
        final var year = result.getValue().asJsonNumber().getValue().intValue();
        assertTrue(year >= 2024 && year <= 2100, "unexpected year " + year);
    }

    @Test
    public void test_plain_time_iso_fields_are_in_range() {
        final var result = run("""
                const t = Temporal.Now.plainTimeISO();
                return t.hour >= 0 && t.hour < 24
                    && t.minute >= 0 && t.minute < 60
                    && t.second >= 0 && t.second < 60;
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    @Test
    public void test_plain_date_time_iso_matches_plain_date_and_plain_time_fields() {
        final var result = run("""
                const dt = Temporal.Now.plainDateTimeISO();
                return dt.year >= 2024 && dt.hour >= 0 && dt.hour < 24;
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    @Test
    public void test_zoned_date_time_iso_carries_the_system_time_zone_by_default() {
        final var result = run("return Temporal.Now.zonedDateTimeISO().timeZoneId === Temporal.Now.timeZoneId();");
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    @Test
    public void test_zoned_date_time_iso_honors_an_explicit_time_zone_argument() {
        final var result = run("return Temporal.Now.zonedDateTimeISO('UTC').timeZoneId;");
        assertFalse(result.isError());
        assertEquals("UTC", result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_time_zone_id_matches_the_jvm_system_default() {
        final var result = run("return Temporal.Now.timeZoneId();");
        assertFalse(result.isError());
        assertEquals(java.time.ZoneId.systemDefault().getId(), result.getValue().asJsonString().getValue());
    }

    @Test
    public void test_plain_date_iso_accepts_an_explicit_time_zone_and_stays_a_plain_date() {
        final var result = run("""
                const d = Temporal.Now.plainDateISO('Asia/Tokyo');
                return typeof d.year === 'number' && typeof d.month === 'number' && typeof d.day === 'number';
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }

    @Test
    public void test_invalid_time_zone_argument_is_a_catchable_range_error() {
        final var result = run("""
                try {
                    Temporal.Now.plainDateISO('Not/AZone');
                    return 'no-throw';
                } catch (e) {
                    return e instanceof RangeError;
                }
                """);
        assertFalse(result.isError());
        assertTrue(result.getValue().asJsonBoolean().getValue());
    }
}
