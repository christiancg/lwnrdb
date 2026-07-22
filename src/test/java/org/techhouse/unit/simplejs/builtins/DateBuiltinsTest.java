package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class DateBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Date.now() is a positive number
    @Test
    public void test_now() {
        assertEquals("number", str("typeof Date.now()"));
        assertTrue(bool("Date.now() > 0"));
    }

    // new Date(ms).getTime() round-trips the epoch millis
    @Test
    public void test_get_time() {
        assertEquals(0, num("new Date(0).getTime()"));
        assertEquals(1000, num("new Date(1000).getTime()"));
    }

    // typeof a date is object
    @Test
    public void test_typeof() {
        assertEquals("object", str("typeof new Date()"));
    }

    // ISO string round-trip
    @Test
    public void test_iso_round_trip() {
        assertEquals("2020-01-02T03:04:05.006Z", str("new Date('2020-01-02T03:04:05.006Z').toISOString()"));
    }

    // UTC component getters
    @Test
    public void test_utc_components() {
        assertEquals(2020, num("new Date('2020-01-02T03:04:05.006Z').getUTCFullYear()"));
        assertEquals(0, num("new Date('2020-01-02T03:04:05.006Z').getUTCMonth()"));
        assertEquals(2, num("new Date('2020-01-02T03:04:05.006Z').getUTCDate()"));
        assertEquals(3, num("new Date('2020-01-02T03:04:05.006Z').getUTCHours()"));
        assertEquals(4, num("new Date('2020-01-02T03:04:05.006Z').getUTCMinutes()"));
        assertEquals(5, num("new Date('2020-01-02T03:04:05.006Z').getUTCSeconds()"));
        assertEquals(6, num("new Date('2020-01-02T03:04:05.006Z').getUTCMilliseconds()"));
    }

    // getUTCDay: 2020-01-02 was a Thursday (4)
    @Test
    public void test_utc_day() {
        assertEquals(4, num("new Date('2020-01-02T00:00:00Z').getUTCDay()"));
    }

    // multi-argument constructor interpreted in UTC
    @Test
    public void test_component_constructor() {
        assertEquals(2020, num("new Date(2020, 0, 2).getUTCFullYear()"));
        assertEquals(2, num("new Date(2020, 0, 2).getUTCDate()"));
    }

    // Number(date) and valueOf equal getTime
    @Test
    public void test_number_coercion() {
        assertEquals(500, num("+new Date(500)"));
        assertEquals(500, num("new Date(500).valueOf()"));
    }

    // JSON.stringify emits the ISO string
    @Test
    public void test_json_stringify() {
        assertEquals("\"1970-01-01T00:00:00.000Z\"", str("JSON.stringify(new Date(0))"));
    }

    // invalid date -> NaN time, and toJSON is null
    @Test
    public void test_invalid_date() {
        assertTrue(bool("isNaN(new Date('not a date').getTime())"));
        assertEquals("null", str("JSON.stringify(new Date('not a date'))"));
    }

    // Date.parse of a bad string is NaN; of a good ISO string is the epoch
    @Test
    public void test_parse() {
        assertTrue(bool("isNaN(Date.parse('garbage'))"));
        assertEquals(0, num("Date.parse('1970-01-01T00:00:00.000Z')"));
    }

    // Date.UTC builds epoch millis from UTC components
    @Test
    public void test_date_utc() {
        assertEquals(0, num("Date.UTC(1970, 0, 1)"));
    }

    // setTime mutates and returns the new time
    @Test
    public void test_set_time() {
        assertEquals(1234, num("let d = new Date(0); d.setTime(1234); d.getTime()"));
    }

    // setUTCFullYear mutates the year
    @Test
    public void test_set_full_year() {
        assertEquals(1999, num("let d = new Date('2020-06-15T00:00:00Z'); d.setUTCFullYear(1999); d.getUTCFullYear()"));
    }

    // local (non-UTC) getters mirror the UTC ones in this sandbox
    @Test
    public void test_local_getters() {
        assertEquals(2020, num("new Date('2020-01-02T03:04:05.006Z').getFullYear()"));
        assertEquals(0, num("new Date('2020-01-02T03:04:05.006Z').getMonth()"));
        assertEquals(2, num("new Date('2020-01-02T03:04:05.006Z').getDate()"));
        assertEquals(4, num("new Date('2020-01-02T00:00:00Z').getDay()"));
        assertEquals(3, num("new Date('2020-01-02T03:04:05.006Z').getHours()"));
        assertEquals(4, num("new Date('2020-01-02T03:04:05.006Z').getMinutes()"));
        assertEquals(5, num("new Date('2020-01-02T03:04:05.006Z').getSeconds()"));
        assertEquals(6, num("new Date('2020-01-02T03:04:05.006Z').getMilliseconds()"));
    }

    // toString / toUTCString produce a readable UTC string; getTimezoneOffset is 0
    @Test
    public void test_to_string_and_offset() {
        assertEquals("Thu Jan 01 1970 00:00:00 GMT+0000 (Coordinated Universal Time)", str("new Date(0).toString()"));
        assertTrue(bool("new Date(0).toUTCString().indexOf('1970') >= 0"));
        assertEquals(0, num("new Date(0).getTimezoneOffset()"));
    }

    // string coercion of a date uses toString
    @Test
    public void test_string_coercion() {
        assertEquals("Thu Jan 01 1970 00:00:00 GMT+0000 (Coordinated Universal Time)", str("'' + new Date(0)"));
        assertEquals("Invalid Date", str("'' + new Date('nope')"));
    }

    // remaining component setters mutate their field
    @Test
    public void test_component_setters() {
        assertEquals(5, num("let d = new Date(0); d.setUTCMonth(5); d.getUTCMonth()"));
        assertEquals(20, num("let d = new Date(0); d.setUTCDate(20); d.getUTCDate()"));
        assertEquals(11, num("let d = new Date(0); d.setUTCHours(11); d.getUTCHours()"));
        assertEquals(22, num("let d = new Date(0); d.setUTCMinutes(22); d.getUTCMinutes()"));
        assertEquals(33, num("let d = new Date(0); d.setUTCSeconds(33); d.getUTCSeconds()"));
        assertEquals(44, num("let d = new Date(0); d.setUTCMilliseconds(44); d.getUTCMilliseconds()"));
    }

    // new Date(dateValue) copies the time
    @Test
    public void test_copy_constructor() {
        assertEquals(1500, num("let a = new Date(1500); new Date(a).getTime()"));
    }

    // an unknown member is undefined
    @Test
    public void test_unknown_member() {
        assertEquals("undefined", str("typeof new Date(0).nope"));
    }

    // getters on an invalid date return NaN
    @Test
    public void test_invalid_getters() {
        assertTrue(bool("isNaN(new Date('nope').getUTCFullYear())"));
    }
}
