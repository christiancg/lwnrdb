package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class DateProgramTest {
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
    public void test_two_digit_year() {
        assertEquals(1999, num("new Date(99, 0, 1).getFullYear()"));
    }

    @Test
    public void test_four_digit_year() {
        assertEquals(2020, num("new Date(2020, 0, 1).getFullYear()"));
    }

    @Test
    public void test_parse_a_date_time_string() {
        assertEquals(2020, num("new Date(Date.parse('2020-01-02T03:04:05')).getUTCFullYear()"));
    }

    @Test
    public void test_parse_a_date_only_string() {
        assertEquals("2020,2", str("""
                const d = new Date(Date.parse('2020-01-02'));
                d.getUTCFullYear() + ',' + d.getUTCDate()
                """));
    }

    @Test
    public void test_parse_an_invalid_string() {
        assertTrue(bool("Number.isNaN(new Date('nonsense').getTime())"));
    }

    @Test
    public void test_set_time() {
        assertEquals(0, num("const d = new Date(1000); d.setTime(0); d.getTime()"));
    }

    @Test
    public void test_set_time_without_an_argument() {
        assertTrue(bool("const d = new Date(0); d.setTime(); Number.isNaN(d.getTime())"));
    }

    @Test
    public void test_set_full_year_keeps_the_rest() {
        assertEquals("2000,1,2", str("""
                const d = new Date(Date.UTC(1990, 1, 2));
                d.setUTCFullYear(2000);
                d.getUTCFullYear() + ',' + d.getUTCMonth() + ',' + d.getUTCDate()
                """));
    }

    @Test
    public void test_set_month_with_a_day() {
        assertEquals("5,7", str("""
                const d = new Date(Date.UTC(2020, 0, 1));
                d.setUTCMonth(5, 7);
                d.getUTCMonth() + ',' + d.getUTCDate()
                """));
    }

    @Test
    public void test_set_hours_with_optional_parts() {
        assertEquals("1,2,3", str("""
                const d = new Date(Date.UTC(2020, 0, 1));
                d.setUTCHours(1, 2, 3);
                d.getUTCHours() + ',' + d.getUTCMinutes() + ',' + d.getUTCSeconds()
                """));
    }

    @Test
    public void test_set_minutes_with_seconds() {
        assertEquals("4,5", str("""
                const d = new Date(Date.UTC(2020, 0, 1));
                d.setUTCMinutes(4, 5);
                d.getUTCMinutes() + ',' + d.getUTCSeconds()
                """));
    }

    @Test
    public void test_set_seconds_with_milliseconds() {
        assertEquals("6,7", str("""
                const d = new Date(Date.UTC(2020, 0, 1));
                d.setUTCSeconds(6, 7);
                d.getUTCSeconds() + ',' + d.getUTCMilliseconds()
                """));
    }

    @Test
    public void test_time_clip() {
        assertTrue(bool("Number.isNaN(new Date(8.64e15 + 1).getTime())"));
    }

    @Test
    public void test_to_iso_string_of_an_invalid_date() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Date(NaN).toISOString()"));
    }

    @Test
    public void test_to_iso_string_of_the_epoch() {
        assertEquals("1970-01-01T00:00:00.000Z", str("new Date(0).toISOString()"));
    }

    @Test
    public void test_utc_with_only_a_year() {
        assertEquals(2020, num("new Date(Date.UTC(2020)).getUTCFullYear()"));
    }

    @Test
    public void test_to_primitive_number_hint() {
        assertEquals(0, num("new Date(0)[Symbol.toPrimitive]('number')"));
    }

    @Test
    public void test_to_primitive_string_hint() {
        assertTrue(bool("typeof new Date(0)[Symbol.toPrimitive]('string') === 'string'"));
    }

    @Test
    public void test_to_primitive_default_hint() {
        assertTrue(bool("new Date(0)[Symbol.toPrimitive]('default') === new Date(0)[Symbol.toPrimitive]('string')"));
    }

    @Test
    public void test_to_primitive_unknown_hint() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Date(0)[Symbol.toPrimitive]('nope')"));
    }

    @Test
    public void test_to_primitive_rejects_a_primitive_receiver() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Date.prototype[Symbol.toPrimitive].call(1, 'number')"));
    }

    @Test
    public void test_date_method_brand_check() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Date.prototype.getTime.call({})"));
    }

    @Test
    public void test_string_concatenation_uses_the_string_hint() {
        assertTrue(bool("('' + new Date(0)) === new Date(0).toString()"));
    }

    @Test
    public void test_subtraction_uses_the_number_hint() {
        assertEquals(1000, num("new Date(2000) - new Date(1000)"));
    }

    @Test
    public void test_invalid_date_string() {
        assertEquals("Invalid Date", str("new Date(NaN).toString()"));
    }

    @Test
    public void test_invalid_date_components() {
        assertTrue(bool("Number.isNaN(new Date(NaN).getUTCFullYear())"));
    }

    @Test
    public void test_value_of() {
        assertEquals(1234, num("new Date(1234).valueOf()"));
    }

    @Test
    public void test_to_temporal_instant() {
        assertTrue(bool("new Date(123456789).toTemporalInstant().epochNanoseconds === 123456789000000n"));
    }

    @Test
    public void test_to_temporal_instant_invalid_date_throws() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Date(NaN).toTemporalInstant()"));
    }

    @Test
    public void test_to_temporal_instant_wrong_receiver_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Date.prototype.toTemporalInstant.call({})"));
    }
}
