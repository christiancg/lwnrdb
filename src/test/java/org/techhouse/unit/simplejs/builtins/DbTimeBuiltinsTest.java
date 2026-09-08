package org.techhouse.unit.simplejs.builtins;

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

public class DbTimeBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Omitted components default to midnight
    @Test
    public void test_constructor_defaults() {
        assertEquals(0, num("new DbTime().hour"));
        assertEquals(0, num("new DbTime().minute"));
        assertEquals(0, num("new DbTime().second"));
    }

    // Every component is readable back off an all-argument construction
    @Test
    public void test_all_argument_construction() {
        assertEquals(9, num("new DbTime(9, 30, 15).hour"));
        assertEquals(30, num("new DbTime(9, 30, 15).minute"));
        assertEquals(15, num("new DbTime(9, 30, 15).second"));
    }

    // An out-of-range component is a RangeError, including a leap-second-shaped 60th second
    @Test
    public void test_out_of_range_components() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbTime(24, 0, 0)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbTime(0, 60, 0)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbTime(23, 59, 60)"));
    }

    // String coercion is the EJson wire form
    @Test
    public void test_string_coercion_is_the_wire_form() {
        assertEquals("#time(09:30:15)", str("String(new DbTime(9, 30, 15))"));
        assertEquals("#time(09:30:15)", str("new DbTime(9, 30, 15).toJSON()"));
    }

    // typeof is "object" and the brand comes from the prototype's toStringTag
    @Test
    public void test_type_and_brand() {
        assertEquals("object", str("typeof new DbTime()"));
        assertEquals("[object DbTime]", str("Object.prototype.toString.call(new DbTime())"));
    }

    // Calling the constructor without new throws
    @Test
    public void test_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DbTime(1, 2, 3)"));
    }

    // from accepts an instance, both string forms, a property bag and a Temporal.PlainTime
    @Test
    public void test_from_accepts_every_input_shape() {
        assertEquals(9, num("DbTime.from(new DbTime(9, 30, 15)).hour"));
        assertEquals(9, num("DbTime.from('#time(09:30:15)').hour"));
        assertEquals(30, num("DbTime.from('09:30:15').minute"));
        assertEquals(15, num("DbTime.from({ hour: 9, minute: 30, second: 15 }).second"));
        assertEquals(9, num("DbTime.from(Temporal.PlainTime.from('09:30:15')).hour"));
    }

    // from rejects a value that is not a time at all
    @Test
    public void test_from_rejects_other_values() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DbTime.from(42)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("DbTime.from('not a time')"));
    }

    // toTemporal bridges to the real Temporal.PlainTime, so its arithmetic is reachable
    @Test
    public void test_to_temporal() {
        assertTrue(bool("new DbTime(9, 30).toTemporal() instanceof Temporal.PlainTime"));
        assertEquals("10:30:00", str("new DbTime(9, 30).toTemporal().add({ hours: 1 }).toString()"));
    }

    // A subclass instance keeps the wrapped value reachable through the prototype accessors
    @Test
    public void test_subclass_wrapping() {
        assertEquals(9, num("class T extends DbTime {}; new T(9, 30).hour"));
    }

    // A subclass wrapper is unwrapped by the accessors, the methods and from()
    @Test
    public void test_subclass_receiver_is_unwrapped() {
        assertEquals("object", str("class D extends DbTime {}; typeof new D(9, 30).toTemporal()"));
        assertTrue(bool("class D extends DbTime {}; DbTime.from(new D(9, 30)) instanceof DbTime"));
    }

    // A foreign receiver is rejected by every prototype accessor and method
    @Test
    public void test_foreign_receiver_is_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(DbTime.prototype, 'hour').get.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DbTime.prototype.toJSON.call({})"));
    }

    // A non-finite component is a RangeError, in both the constructor and the property bag
    @Test
    public void test_non_finite_components_are_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbTime(NaN)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("DbTime.from({ hour: Infinity })"));
    }
}
