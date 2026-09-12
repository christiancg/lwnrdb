package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.BuiltinLengths;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

// A builtin's `length` is observable but cannot be derived from the Java lambda (which always takes
public class BuiltinLengthsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_default_length_is_one() {
        assertEquals(1, BuiltinLengths.lengthOf("Array.prototype", "map"));
        assertEquals(1, BuiltinLengths.lengthOf("Nonexistent.prototype", "whatever"));
    }

    @Test
    public void test_listed_lengths() {
        assertEquals(0, BuiltinLengths.lengthOf("Array.prototype", "pop"));
        assertEquals(2, BuiltinLengths.lengthOf("Array.prototype", "splice"));
        assertEquals(4, BuiltinLengths.lengthOf("Date.prototype", "setHours"));
        assertEquals(2, BuiltinLengths.lengthOf("DataView.prototype", "setUint16"));
    }

    @Test
    public void test_lengths_visible_from_script() {
        assertEquals("1,2,0,1", str("[Array.prototype.push.length, Array.prototype.slice.length,"
                + " Array.prototype.pop.length, Array.prototype.join.length].join(',')"));
        assertEquals("2,0,1", str("[String.prototype.slice.length, String.prototype.trim.length,"
                + " String.prototype.charAt.length].join(',')"));
        assertEquals("2,1,0", str("[Promise.prototype.then.length, Function.prototype.call.length,"
                + " Object.prototype.toString.length].join(',')"));
    }

    @Test
    public void test_length_descriptor_attributes() {
        assertEquals("false,false,true",
                str("const d = Object.getOwnPropertyDescriptor(Array.prototype.slice, 'length');"
                        + " [d.writable, d.enumerable, d.configurable].join(',')"));
    }

    @Test
    public void test_host_boundary_lengths() {
        assertEquals("2,1,6,3", str("[Geo.length, Vector.length, DbDateTime.length, DbTime.length].join(',')"));
        assertEquals("0,0", str("[Geo.prototype.toString.length, Geo.prototype.toJSON.length].join(',')"));
        assertEquals("0,1,2",
                str("[crypto.randomUUID.length, crypto.getRandomValues.length, crypto.hash.length].join(',')"));
    }
}
