package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class TemporalNowBuiltinsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Temporal.Now is a plain object of functions, not a constructor: Object.getPrototypeOf answers
    // Object.prototype (via Temporal.Now's own setProto call), never a Temporal.Now.prototype
    @Test
    public void test_now_is_a_plain_namespace_object() {
        assertTrue(
                bool("typeof Temporal.Now === 'object' && Object.getPrototypeOf(Temporal.Now) === Object.prototype"));
    }

    @Test
    public void test_now_is_not_constructible() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.Now()"));
    }

    // Object.prototype.toString reports the real own [Symbol.toStringTag] on both Temporal and
    // Temporal.Now, mirroring how Math/JSON/Reflect are tagged
    @Test
    public void test_to_string_tags() {
        assertEquals("[object Temporal]", str("Object.prototype.toString.call(Temporal)"));
        assertEquals("[object Temporal.Now]", str("Object.prototype.toString.call(Temporal.Now)"));
    }

    @Test
    public void test_instant_returns_real_temporal_instant() {
        assertTrue(bool("Temporal.Now.instant() instanceof Temporal.Instant"));
    }

    @Test
    public void test_plain_date_iso_returns_real_temporal_plain_date() {
        assertTrue(bool("Temporal.Now.plainDateISO() instanceof Temporal.PlainDate"));
    }

    @Test
    public void test_plain_time_iso_returns_real_temporal_plain_time() {
        assertTrue(bool("Temporal.Now.plainTimeISO() instanceof Temporal.PlainTime"));
    }

    @Test
    public void test_plain_date_time_iso_returns_real_temporal_plain_date_time() {
        assertTrue(bool("Temporal.Now.plainDateTimeISO() instanceof Temporal.PlainDateTime"));
    }

    @Test
    public void test_zoned_date_time_iso_returns_real_temporal_zoned_date_time() {
        assertTrue(bool("Temporal.Now.zonedDateTimeISO() instanceof Temporal.ZonedDateTime"));
    }

    @Test
    public void test_time_zone_id_returns_nonempty_string() {
        assertTrue(bool("typeof Temporal.Now.timeZoneId() === 'string' && Temporal.Now.timeZoneId().length > 0"));
    }

    // With no argument every zone-aware member defaults to the system time zone, matching
    // Temporal.Now.timeZoneId()
    @Test
    public void test_zoned_date_time_iso_defaults_to_system_time_zone() {
        assertTrue(bool("Temporal.Now.zonedDateTimeISO().timeZoneId === Temporal.Now.timeZoneId()"));
    }

    // An explicit string argument overrides the system default
    @Test
    public void test_explicit_time_zone_argument_is_honored() {
        assertEquals("UTC", str("Temporal.Now.zonedDateTimeISO('UTC').timeZoneId"));
        assertTrue(bool("Temporal.Now.plainDateISO('Asia/Tokyo') instanceof Temporal.PlainDate"));
        assertTrue(bool("Temporal.Now.plainTimeISO('Asia/Tokyo') instanceof Temporal.PlainTime"));
        assertTrue(bool("Temporal.Now.plainDateTimeISO('Asia/Tokyo') instanceof Temporal.PlainDateTime"));
    }

    // A Temporal.ZonedDateTime argument reuses its own time zone (ToTemporalTimeZoneIdentifier)
    @Test
    public void test_zoned_date_time_argument_reuses_its_time_zone() {
        assertEquals("America/New_York", str("Temporal.Now.zonedDateTimeISO("
                + "Temporal.ZonedDateTime.from('2020-06-15T10:00:00-04:00[America/New_York]')).timeZoneId"));
    }

    @Test
    public void test_invalid_time_zone_argument_throws_range_error() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Temporal.Now.plainDateISO('Not/AZone')"));
    }
}
