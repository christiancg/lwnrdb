package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.EJsonInterop;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsDate;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

// Date's local-time surface, the exact to*String formats, the Error own-property contract and the
// JSON wrapper/replacer handling.
public class DateErrorJsonProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String caught(String expression) {
        return str(
                "(function(){ try { " + expression + "; return 'no throw'; }" + " catch (e) { return e.name; } })()");
    }

    // toString/toDateString/toTimeString/toUTCString match the spec's grammars
    @Test
    public void test_to_string_formats() {
        assertTrue(bool("/^(Sun|Mon|Tue|Wed|Thu|Fri|Sat) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
                + " [0-9]{2} [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT[+-][0-9]{4}( \\(.+\\))?$/"
                + ".test(new Date(0).toString())"));
        assertTrue(bool("/^(Sun|Mon|Tue|Wed|Thu|Fri|Sat) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)"
                + " [0-9]{2} [0-9]{4}$/.test(new Date(0).toDateString())"));
        assertTrue(bool(
                "/^[0-9]{2}:[0-9]{2}:[0-9]{2} GMT[+-][0-9]{4}( \\(.+\\))?$/" + ".test(new Date(0).toTimeString())"));
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", str("new Date(0).toUTCString()"));
    }

    // An invalid date reports "Invalid Date" from every string form
    @Test
    public void test_invalid_date_strings() {
        assertEquals("Invalid Date", str("new Date(NaN).toString()"));
        assertEquals("Invalid Date", str("new Date(NaN).toDateString()"));
        assertEquals("Invalid Date", str("new Date(NaN).toTimeString()"));
        assertEquals("Invalid Date", str("new Date(NaN).toUTCString()"));
        assertTrue(bool("Number.isNaN(new Date(NaN).getTimezoneOffset())"));
    }

    // A negative year keeps its sign in the padded four-digit form
    @Test
    public void test_negative_year_padding() {
        assertTrue(bool("new Date(Date.UTC(-1, 0, 1)).toUTCString().indexOf('-0001') !== -1"));
    }

    // Date.parse reads back everything the three string forms produce
    @Test
    public void test_parse_round_trip() {
        assertTrue(bool("var z = new Date(0); Date.parse(z.toString()) === 0"));
        assertTrue(bool("var z = new Date(0); Date.parse(z.toUTCString()) === 0"));
        assertTrue(bool("var z = new Date(0); Date.parse(z.toISOString()) === 0"));
        assertEquals(0, num("Date.parse('1970')"));
    }

    // toISOString uses the extended year form outside 0000-9999, and parse accepts it back
    @Test
    public void test_extended_year_iso() {
        assertEquals("-271821-04-20T00:00:00.000Z", str("new Date(-8640000000000000).toISOString()"));
        assertEquals("+275760-09-13T00:00:00.000Z", str("new Date(8640000000000000).toISOString()"));
        assertEquals(-8.64e15, num("Date.parse('-271821-04-20T00:00:00.000Z')"));
        assertTrue(bool("Number.isNaN(Date.parse('+275760-09-13T00:00:00.001Z'))"));
    }

    // Local accessors agree with the UTC ones once the zone offset is applied
    @Test
    public void test_local_accessors_track_the_zone_offset() {
        assertTrue(bool("var d = new Date(0);"
                + "d.getHours() === new Date(d.getTime() - d.getTimezoneOffset() * 60000).getUTCHours()"));
        assertTrue(bool("var d = new Date(2016, 6, 1); d.getMonth() === 6 && d.getDate() === 1"
                + " && d.getFullYear() === 2016"));
    }

    // The leading setter argument is always coerced, so a missing one invalidates the date
    @Test
    public void test_setter_argument_coercion() {
        assertTrue(bool("Number.isNaN(new Date(2016, 6).setMilliseconds())"));
        assertTrue(bool("var d = new Date(2016, 6); d.setMilliseconds(2);"
                + "d.getTime() === new Date(2016, 6, 1, 0, 0, 0, 2).getTime()"));
    }

    // toJSON is generic over any receiver with a toISOString
    @Test
    public void test_to_json_is_generic() {
        assertEquals("global",
                str("var o = {toISOString: function(){ return 'global'; }};" + "Date.prototype.toJSON.call(o)"));
        assertEquals("TypeError", caught("Date.prototype.toJSON.call(undefined)"));
        assertTrue(bool("Date.prototype.toJSON.call({valueOf: function(){ return Infinity; },"
                + " toISOString: function(){ return 'x'; }}) === null"));
    }

    // Date() called as a plain function answers a string, not a Date
    @Test
    public void test_date_called_as_a_function() {
        assertEquals("string", str("typeof Date()"));
    }

    // JsDate stays epoch-millis internally, so the host-side ISO mapping is untouched
    @Test
    public void test_ejson_interop_is_unaffected() {
        assertEquals("1970-01-01T00:00:00.000Z",
                ((org.techhouse.ejson.elements.JsonString) EJsonInterop.toEjson(new JsDate(0))).getValue());
    }

    // An error's message is an own property only when one was supplied
    @Test
    public void test_error_message_own_property() {
        assertTrue(bool("Object.prototype.hasOwnProperty.call(new Error('m'), 'message')"));
        assertTrue(bool("!Object.prototype.hasOwnProperty.call(new Error(), 'message')"));
        assertTrue(bool("!Object.prototype.hasOwnProperty.call(new AggregateError([], undefined), 'message')"));
        assertTrue(bool("var d = Object.getOwnPropertyDescriptor(new Error('m'), 'message');"
                + "d.writable && !d.enumerable && d.configurable"));
    }

    // Error.prototype.toString reads through the prototype chain and drops an empty name
    @Test
    public void test_error_to_string() {
        assertEquals("msg", str("var e = new Error('msg'); e.name = ''; e.toString()"));
        assertEquals("Error", str("new Error().toString()"));
        assertEquals("Error: msg", str("new Error('msg').toString()"));
        assertEquals("EvalError", caught("var e = new Error('m');"
                + "Object.defineProperty(e, 'name', {get: function(){ throw new EvalError(); }}); e.toString()"));
    }

    // The message argument is ToString'd and `cause` is installed through HasProperty
    @Test
    public void test_error_argument_coercion() {
        assertEquals("1", str("new Error({toString: function(){ return '1'; }}).message"));
        assertTrue(bool("Object.prototype.hasOwnProperty.call(new Error('m', {cause: 1}), 'cause')"));
        assertTrue(bool("!Object.prototype.hasOwnProperty.call(new Error('m', {}), 'cause')"));
        assertEquals("EvalError", caught("new Error('m', {get cause(){ throw new EvalError(); }})"));
    }

    // AggregateError drains any iterable, in message-then-errors order
    @Test
    public void test_aggregate_error_errors() {
        assertEquals(3, num("new AggregateError(new Set([1, 2, 3])).errors.length"));
        assertEquals("1,2", str("new AggregateError([1, 2]).errors.join(',')"));
    }

    // SuppressedError installs message, error and suppressed in that order
    @Test
    public void test_suppressed_error_property_order() {
        assertEquals("message,error,suppressed",
                str("Object.getOwnPropertyNames(new SuppressedError({}, {}, 'm')).join(',')"));
    }

    // A boxed Number/String serializes through ToNumber/ToString, and a non-finite one becomes null
    @Test
    public void test_json_stringify_wrappers() {
        assertEquals("[42]",
                str("var n = new Number(2); n.valueOf = function(){ return 42; };" + " JSON.stringify([n])"));
        assertEquals("[null]", str("JSON.stringify([new Number(Infinity)])"));
        assertEquals("{\"key\":\"toString\"}", str("var s = new String('str');"
                + " s.toString = function(){ return 'toString'; }; JSON.stringify({key: s})"));
    }

    // The PropertyList replacer dictates the output key order
    @Test
    public void test_json_stringify_replacer_order() {
        assertEquals("{\"c\":3,\"b\":1,\"a\":2}", str("JSON.stringify({b: 1, a: 2, c: 3}, ['c', 'b', 'a'])"));
    }

    // JSON.parse produces ordinary objects, and a "__proto__" key stays an ordinary property
    @Test
    public void test_json_parse_objects_are_ordinary() {
        assertTrue(bool("Object.getPrototypeOf(JSON.parse('{\"a\":1}')) === Object.prototype"));
        assertTrue(bool("Object.getPrototypeOf(JSON.parse('{\"__proto__\":[]}')) === Object.prototype"));
    }

    // A lone surrogate is escaped rather than emitted raw
    @Test
    public void test_json_stringify_lone_surrogate() {
        assertEquals("\"\\ud834\"", str("JSON.stringify('\\ud834')"));
        assertEquals("\"\uD834\uDD1E\"", str("JSON.stringify('\\ud834\\udd1e')"));
    }
}
