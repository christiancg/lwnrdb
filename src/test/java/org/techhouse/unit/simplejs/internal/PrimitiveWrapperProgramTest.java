package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.EJson;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class PrimitiveWrapperProgramTest {
    private static final EJson EJSON = new EJson();

    private static String run(String source) {
        final var result = new SimpleJs().run(source, SimpleHostBindings.empty());
        assertFalse(result.isError(), () -> result.getErrorName() + ": " + result.getErrorMessage());
        return EJSON.toJson(result.getValue());
    }

    private static String errorName(String source) {
        final var result = new SimpleJs().run(source, SimpleHostBindings.empty());
        assertTrue(result.isError(), "expected an error result");
        return result.getErrorName();
    }

    // A boxed primitive is an object, and each boxing is a distinct one
    @Test
    public void test_wrapper_identity_and_typeof() {
        assertEquals("[\"object\",\"object\",\"object\",\"object\",\"object\"]",
                run("return [typeof Object(1), typeof Object('a'), typeof Object(true), typeof Object(1n),"
                        + " typeof Object(Symbol())]"));
        assertEquals("false", run("const s = Symbol(); return Object(s) === Object(s)"));
        assertEquals("true", run("const o = {}; return Object(o) === o"));
    }

    // Object called with new boxes exactly as Object called as a function does
    @Test
    public void test_new_object_boxes_primitives() {
        assertEquals("[true,true,true,true]",
                run("return [new Object('x').constructor === String, new Object(1).constructor === Number,"
                        + " new Object(true).constructor === Boolean, new Object(1n).constructor === BigInt]"));
        assertEquals("true", run("return new Object(1) == 1"));
        assertEquals("\"object\"", run("return typeof new Object(null)"));
    }

    // A String wrapper owns one non-writable enumerable property per code unit
    @Test
    public void test_string_wrapper_own_indices() {
        assertEquals("[\"0\",\"1\",\"length\"]", run("return Object.getOwnPropertyNames(new String('ab'))"));
        assertEquals("[\"0\",\"1\"]", run("return Object.keys(new String('ab'))"));
        assertEquals("true", run("return '0' in new String('ab')"));
        assertEquals("false", run("return '2' in new String('ab')"));
        assertEquals("[\"a\",\"b\"]",
                run("const r = []; for (const k in new String('ab')) r.push(new String('ab')[k]);" + " return r"));
        assertEquals("{\"value\":\"a\",\"writable\":false,\"enumerable\":true,\"configurable\":false}",
                run("return Object.getOwnPropertyDescriptor(new String('ab'), '0')"));
    }

    // length is the wrapper's own non-enumerable property, not the prototype's
    @Test
    public void test_string_wrapper_length() {
        assertEquals("[3,true,false]", run("const s = new String('abc');"
                + " return [s.length, s.hasOwnProperty('length'), s.propertyIsEnumerable('length')]"));
        assertEquals("[\"length\"]", run("return Object.getOwnPropertyNames(new String(''))"));
        assertEquals("TypeError", errorName("new String('ab')[0] = 'z'"));
    }

    // Each family's prototype methods unwrap the receiver from the wrapper
    @Test
    public void test_method_dispatch_through_wrapper() {
        assertEquals("\"b\"", run("return new String('ab').charAt(1)"));
        assertEquals("\"5.00\"", run("return new Number(5).toFixed(2)"));
        assertEquals("\"ff\"", run("return Number.prototype.toString.call(Object(255), 16)"));
        assertEquals("false", run("return new Boolean(false).valueOf()"));
        assertEquals("\"1\"", run("return Object(1n).toString()"));
        assertEquals("\"Symbol(66)\"", run("return Object(Symbol('66')).toString()"));
        assertEquals("\"x\"", run("return Object(Symbol('x')).description"));
    }

    // A wrapper receiver is accepted, anything else is still rejected
    @Test
    public void test_incompatible_receiver_still_rejected() {
        assertEquals("TypeError", errorName("Boolean.prototype.valueOf.call({})"));
        assertEquals("TypeError", errorName("Number.prototype.toFixed.call('1')"));
        assertEquals("true", run("return Boolean.prototype.valueOf.call(new Boolean(true))"));
    }

    // ToPrimitive runs the ordinary valueOf/toString path rather than reading the boxed slot
    @Test
    public void test_redefined_valueof_beats_the_slot() {
        assertEquals("8", run("const o = new String('ab'); o.valueOf = function () { return 7 }; return o + 1"));
        assertEquals("\"seven\"",
                run("const o = new Number(1); o.toString = function () { return 'seven' };" + " return `${o}`"));
        assertEquals("4", run("const o = new Boolean(false); o.valueOf = function () { return 3 }; return o + 1"));
    }

    // The wrapper is an object everywhere it matters, including truthiness and mixed BigInt arithmetic
    @Test
    public void test_wrapper_is_an_object() {
        assertEquals("true", run("return Object(false) ? true : false"));
        assertEquals("TypeError", errorName("Object(1n) + 1"));
        assertEquals("true", run("return new String('ab') instanceof String"));
    }
}
