package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

/**
 * String coercion on the data-only path - the one that must not call back into user code. Array joining
 * reaches it for every element, so each value type has to render itself without a ToPrimitive round trip.
 */
public class DataOnlyStringCoercionTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("const d = new Date(0); [d].join('') === String(d)")).getValue();
    }

    @Test
    public void test_a_date_element_renders_as_the_date_itself_does() {
        assertTrue(bool());
    }

    @Test
    public void test_temporal_elements_render_in_iso_form() {
        assertEquals("PT1H|12:30:00|2026-01-02|2026-01-02T03:04:05Z", str("""
                [
                    Temporal.Duration.from('PT1H'),
                    Temporal.PlainTime.from('12:30'),
                    Temporal.PlainDate.from('2026-01-02'),
                    Temporal.Instant.from('2026-01-02T03:04:05Z')
                ].join('|')
                """));
    }

    @Test
    public void test_the_remaining_temporal_elements_render_in_iso_form() {
        assertEquals("2026-01|01-02|2026-01-02T03:04:00|2026-01-02T03:04:00+00:00[UTC]", str("""
                [
                    Temporal.PlainYearMonth.from('2026-01'),
                    Temporal.PlainMonthDay.from('01-02'),
                    Temporal.PlainDateTime.from('2026-01-02T03:04'),
                    Temporal.ZonedDateTime.from('2026-01-02T03:04[UTC]')
                ].join('|')
                """));
    }

    // The four EJson custom types render as their wire text, which is what makes them round-trip
    @Test
    public void test_the_database_custom_types_render_as_their_wire_text() {
        assertEquals("#geo(1.0,2.0)|#vector(1.0,2.0)|#datetime(2026-01-02T03:04:05)|#time(03:04:05)", str("""
                [
                    Geo.from({ lat: 1, lng: 2 }),
                    Vector.from([1, 2]),
                    DbDateTime.from('2026-01-02T03:04:05'),
                    DbTime.from('03:04:05')
                ].join('|')
                """));
    }

    @Test
    public void test_an_async_generator_element_renders_as_its_brand() {
        assertEquals("[object AsyncGenerator]", str("[(async function* () {})()].join('')"));
    }

    @Test
    public void test_a_generator_and_a_promise_element_render_as_their_brands() {
        assertEquals("[object Generator]|[object Promise]", str("[(function* () {})(), Promise.resolve(1)].join('|')"));
    }

    @Test
    public void test_a_proxy_element_renders_as_its_target() {
        assertEquals("[object Map]", str("[new Proxy(new Map(), {})].join('')"));
    }
}
