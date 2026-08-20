package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TemporalPlainTimeBuiltinsTest {
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
    public void test_default_constructor_fields_are_zero() {
        assertEquals("00:00:00", str("new Temporal.PlainTime().toString()"));
    }

    @Test
    public void test_constructor_with_all_fields() {
        assertEquals("12:30:15.1002", str("new Temporal.PlainTime(12, 30, 15, 100, 200, 0).toString()"));
    }

    @Test
    public void test_constructor_fields_round_trip() {
        assertEquals("01:02:03.004005006", str("new Temporal.PlainTime(1, 2, 3, 4, 5, 6).toString()"));
    }

    @Test
    public void test_field_getters() {
        final var setup = "let t = new Temporal.PlainTime(1, 2, 3, 4, 5, 6); ";
        assertEquals(1, num(setup + "t.hour"));
        assertEquals(2, num(setup + "t.minute"));
        assertEquals(3, num(setup + "t.second"));
        assertEquals(4, num(setup + "t.millisecond"));
        assertEquals(5, num(setup + "t.microsecond"));
        assertEquals(6, num(setup + "t.nanosecond"));
    }

    @Test
    public void test_typeof_is_object() {
        assertEquals("object", str("typeof new Temporal.PlainTime()"));
    }

    @Test
    public void test_to_string_tag() {
        assertEquals("[object Temporal.PlainTime]", str("Object.prototype.toString.call(new Temporal.PlainTime())"));
    }

    @Test
    public void test_constructor_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime(1, 2, 3)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_hour() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(24)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(-1)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_minute_and_second() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 60)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 60)"));
    }

    @Test
    public void test_constructor_rejects_out_of_range_subsecond_fields() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 1000)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 0, 1000)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(0, 0, 0, 0, 0, 1000)"));
    }

    @Test
    public void test_value_of_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).valueOf()"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("+new Temporal.PlainTime(1)"));
    }

    @Test
    public void test_to_json_and_to_locale_string_match_to_string() {
        assertTrue(bool("var t = new Temporal.PlainTime(9, 5, 0); "
                + "t.toJSON() === t.toString() && t.toLocaleString() === t.toString()"));
    }

    @Test
    public void test_equals() {
        assertTrue(bool("new Temporal.PlainTime(1, 2, 3).equals(new Temporal.PlainTime(1, 2, 3))"));
        assertFalse(bool("new Temporal.PlainTime(1, 2, 3).equals(new Temporal.PlainTime(1, 2, 4))"));
        assertTrue(bool("new Temporal.PlainTime(1, 2, 3).equals('01:02:03')"));
    }

    @Test
    public void test_static_compare() {
        assertEquals(-1, num("Temporal.PlainTime.compare(new Temporal.PlainTime(1), new Temporal.PlainTime(2))"));
        assertEquals(0, num("Temporal.PlainTime.compare(new Temporal.PlainTime(1), new Temporal.PlainTime(1))"));
        assertEquals(1, num("Temporal.PlainTime.compare(new Temporal.PlainTime(2), new Temporal.PlainTime(1))"));
    }

    @Test
    public void test_from_string() {
        assertEquals("12:30:00", str("Temporal.PlainTime.from('12:30:00').toString()"));
    }

    @Test
    public void test_from_plain_time_creates_a_new_instance() {
        assertTrue(bool("var a = new Temporal.PlainTime(1, 2, 3); var b = Temporal.PlainTime.from(a); "
                + "a !== b && a.equals(b)"));
    }

    @Test
    public void test_from_object_default_overflow_constrains() {
        assertEquals(23, num("Temporal.PlainTime.from({hour: 25}).hour"));
    }

    @Test
    public void test_from_object_reject_overflow_throws() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("Temporal.PlainTime.from({hour: 25}, {overflow: 'reject'})"));
    }

    @Test
    public void test_with_overrides_given_fields() {
        assertEquals("05:02:03", str("new Temporal.PlainTime(1, 2, 3).with({hour: 5}).toString()"));
    }

    @Test
    public void test_with_rejects_a_real_plain_time() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).with(new Temporal.PlainTime(2))"));
    }

    @Test
    public void test_with_rejects_no_recognized_fields() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).with({})"));
    }

    @Test
    public void test_add_wraps_around_24_hours() {
        assertEquals("01:00:00", str("new Temporal.PlainTime(23, 0, 0).add({hours: 2}).toString()"));
    }

    @Test
    public void test_subtract_wraps_backward() {
        assertEquals("23:00:00", str("new Temporal.PlainTime(1, 0, 0).subtract({hours: 2}).toString()"));
    }

    @Test
    public void test_add_rejects_mixed_sign_duration() {
        assertThrows(RangeErrorException.class,
                () -> Interpreter.run("new Temporal.PlainTime(1).add({hours: 1, minutes: -1})"));
    }

    @Test
    public void test_round_to_minute() {
        assertEquals("12:35:00", str("new Temporal.PlainTime(12, 34, 56).round({smallestUnit: 'minute'}).toString()"));
    }

    @Test
    public void test_round_accepts_a_string_shorthand() {
        assertEquals("13:00:00", str("new Temporal.PlainTime(12, 34, 56).round('hour').toString()"));
    }

    @Test
    public void test_round_requires_options() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Temporal.PlainTime(1).round()"));
    }

    @Test
    public void test_get_iso_fields() {
        assertTrue(bool("var f = new Temporal.PlainTime(1, 2, 3, 4, 5, 6).getISOFields(); "
                + "f.isoHour === 1 && f.isoMinute === 2 && f.isoSecond === 3 && f.isoMillisecond === 4"
                + " && f.isoMicrosecond === 5 && f.isoNanosecond === 6"));
    }

    @Test
    public void test_until_and_since() {
        assertTrue(bool("var d = new Temporal.PlainTime(10, 0, 0).until(new Temporal.PlainTime(12, 30, 0)); "
                + "d.hours === 2 && d.minutes === 30"));
        assertTrue(bool("var d = new Temporal.PlainTime(12, 30, 0).since(new Temporal.PlainTime(10, 0, 0)); "
                + "d.hours === 2 && d.minutes === 30"));
    }

    @Test
    public void test_brand_check_on_a_foreign_receiver() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Temporal.PlainTime.prototype.toString.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("Object.getOwnPropertyDescriptor(Temporal.PlainTime.prototype, 'hour')" + ".get.call({})"));
    }

    @Test
    public void test_unknown_member_is_undefined() {
        assertEquals("undefined", str("typeof new Temporal.PlainTime().nope"));
    }
}
