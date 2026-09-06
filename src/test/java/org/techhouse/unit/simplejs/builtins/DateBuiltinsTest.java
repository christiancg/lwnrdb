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

    // local (non-UTC) getters read the same instant through the JVM default zone
    @Test
    public void test_local_getters() {
        assertEquals(2020, num("new Date('2020-01-02T03:04:05.006Z').getFullYear()"));
        assertEquals(0, num("new Date('2020-01-02T03:04:05.006Z').getMonth()"));
        assertTrue(bool("var d = new Date('2020-01-02T03:04:05.006Z');"
                + "d.getHours() === new Date(d.getTime() - d.getTimezoneOffset() * 60000).getUTCHours()"));
        assertEquals(4, num("new Date('2020-01-02T03:04:05.006Z').getMinutes()"));
        assertEquals(5, num("new Date('2020-01-02T03:04:05.006Z').getSeconds()"));
        assertEquals(6, num("new Date('2020-01-02T03:04:05.006Z').getMilliseconds()"));
    }

    // toString follows the spec's ToDateString grammar and toUTCString the RFC form
    @Test
    public void test_to_string_and_offset() {
        assertTrue(bool("/^(Sun|Mon|Tue|Wed|Thu|Fri|Sat) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
                + " [0-9]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT[+-][0-9]{4}( \\(.+\\))?$/"
                + ".test(new Date(0).toString())"));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", str("new Date(0).toUTCString()"));
        assertTrue(bool("Number.isFinite(new Date(0).getTimezoneOffset())"));
    }

    // string coercion of a date uses toString
    @Test
    public void test_string_coercion() {
        assertEquals(str("new Date(0).toString()"), str("'' + new Date(0)"));
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

    // toLocaleString/Date/Time produce a non-empty locale string, and "Invalid Date" for NaN
    @Test
    public void test_to_locale_string() {
        assertTrue(bool("new Date(0).toLocaleString().length > 0"));
        assertTrue(bool("new Date(0).toLocaleDateString().length > 0"));
        assertTrue(bool("new Date(0).toLocaleTimeString().length > 0"));
        assertEquals("Invalid Date", str("new Date(Number.NaN).toLocaleString()"));
    }

    @Test
    public void setHoursReadsAllFourArguments() {
        final var setup = "let d = new Date(0); d.setUTCHours(1, 2, 3, 4); ";
        assertEquals(1, num(setup + "d.getUTCHours()"));
        assertEquals(2, num(setup + "d.getUTCMinutes()"));
        assertEquals(3, num(setup + "d.getUTCSeconds()"));
        assertEquals(4, num(setup + "d.getUTCMilliseconds()"));
        assertEquals(3723004, num(setup + "d.getTime()"));
    }

    @Test
    public void argumentCoercionOrderIsLeftToRight() {
        final var source = "let order = ''; let d = new Date(0);"
                + " d.setUTCHours({valueOf: () => { order += 'h'; return 1; }},"
                + " {valueOf: () => { order += 'm'; return 2; }},"
                + " {valueOf: () => { order += 's'; return 3; }}); order";
        assertEquals("hms", str(source));
        // An invalid receiver still coerces every argument before bailing out
        assertEquals("hms", str(source.replace("new Date(0)", "new Date(Number.NaN)")));
    }

    @Test
    public void outOfRangeComponentsRollOverInsteadOfThrowing() {
        assertEquals(2, num("let d = new Date(0); d.setUTCHours(25); d.getUTCDate()"));
        assertEquals(1, num("let d = new Date(0); d.setUTCHours(25); d.getUTCHours()"));
        assertEquals(1, num("let d = new Date(0); d.setUTCMonth(12); d.getUTCFullYear() - 1970"));
        assertEquals(1970, num("let d = new Date(0); d.setUTCDate(32); d.getUTCFullYear()"));
        assertEquals(1, num("let d = new Date(0); d.setUTCDate(32); d.getUTCMonth()"));
    }

    @Test
    public void nanArgumentPoisonsTheDate() {
        assertTrue(bool("let d = new Date(0); d.setUTCHours(Number.NaN); isNaN(d.getTime())"));
        assertTrue(bool("let d = new Date(0); d.setUTCHours(1, undefined); isNaN(d.getTime())"));
        assertTrue(bool("let d = new Date(0); d.setUTCMinutes(Number.POSITIVE_INFINITY); isNaN(d.getTime())"));
    }

    @Test
    public void setTimeAppliesTimeClip() {
        assertTrue(bool("let d = new Date(0); d.setTime(8.64e15 + 1); isNaN(d.getTime())"));
        assertEquals(8.64e15, num("let d = new Date(0); d.setTime(8.64e15); d.getTime()"));
        assertEquals(1, num("let d = new Date(0); d.setTime(1.5); d.getTime()"));
    }

    @Test
    public void setFullYearRevivesAnInvalidDate() {
        assertEquals(2000, num("let d = new Date(Number.NaN); d.setUTCFullYear(2000); d.getUTCFullYear()"));
    }

    @Test
    public void symbolToPrimitiveIsPresent() {
        assertEquals("function", str("typeof Date.prototype[Symbol.toPrimitive]"));
        assertEquals("string", str("typeof new Date(0)[Symbol.toPrimitive]('default')"));
        assertEquals("number", str("typeof new Date(0)[Symbol.toPrimitive]('number')"));
        assertEquals("string", str("typeof new Date(0)[Symbol.toPrimitive]('string')"));
        assertTrue(bool("let threw = false;"
                + " try { new Date(0)[Symbol.toPrimitive]('bogus'); } catch (e) { threw = e instanceof TypeError }"
                + " threw"));
    }

    // Reflect.construct(Date, args, newTarget) must link the new instance's prototype to
    // newTarget.prototype (OrdinaryCreateFromConstructor), not always to the intrinsic
    // Date.prototype - this is what makes `class X extends Date {}` and manual subclassing via
    // Reflect.construct observe the right prototype chain and internal [[DateValue]] slot.
    @Test
    public void reflectConstructLinksNewTargetPrototype() {
        assertTrue(bool("""
                var callCount = 0;
                var Ctor = function() { callCount += 1; };
                var instance = Reflect.construct(Date, [64], Ctor);
                Object.getPrototypeOf(instance) === Ctor.prototype
                    && callCount === 0
                    && Date.prototype.getTime.call(instance) === 64
                """));
    }

    // A plain `new Date(...)` (no custom newTarget) still gets the ordinary Date.prototype.
    @Test
    public void plainNewKeepsDatePrototype() {
        assertTrue(bool("Object.getPrototypeOf(new Date(0)) === Date.prototype"));
    }
}
