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

public class VectorBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // An array argument and a variadic argument list build the same vector
    @Test
    public void test_construction() {
        assertEquals(3, num("new Vector([1, 2, 3]).length"));
        assertEquals(3, num("new Vector(1, 2, 3).length"));
        assertEquals(2, num("new Vector([1, 2, 3]).at(1)"));
    }

    // at() follows Array.prototype.at: negative counts from the end, out of range is undefined
    @Test
    public void test_at_semantics() {
        assertEquals(3, num("new Vector([1, 2, 3]).at(-1)"));
        assertEquals("undefined", str("String(new Vector([1, 2, 3]).at(9))"));
    }

    // A single-component vector is legal; an empty one is not
    @Test
    public void test_length_bounds() {
        assertEquals(1, num("new Vector([7]).length"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Vector([])"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Vector()"));
    }

    // A NaN component is kept as-is rather than rejected
    @Test
    public void test_nan_component() {
        assertTrue(bool("Number.isNaN(new Vector([NaN]).at(0))"));
    }

    // String coercion is the EJson wire form
    @Test
    public void test_string_coercion_is_the_wire_form() {
        assertEquals("#vector(1.0,2.0)", str("String(new Vector([1, 2]))"));
        assertEquals("#vector(1.0,2.0)", str("new Vector([1, 2]).toJSON()"));
    }

    // simHash exposes the signature the vector index clusters by
    @Test
    public void test_sim_hash_accessor() {
        assertEquals(16, num("new Vector([1, 2]).simHash.length"));
    }

    // toArray hands the components back as an ordinary array
    @Test
    public void test_to_array() {
        assertEquals(3, num("new Vector([1, 2, 3]).toArray().length"));
        assertTrue(bool("Array.isArray(new Vector([1]).toArray())"));
    }

    // typeof is "object" and the brand comes from the prototype's toStringTag
    @Test
    public void test_type_and_brand() {
        assertEquals("object", str("typeof new Vector([1])"));
        assertEquals("[object Vector]", str("Object.prototype.toString.call(new Vector([1]))"));
    }

    // Calling the constructor without new throws
    @Test
    public void test_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Vector([1])"));
    }

    // from accepts an instance, the wire string and an array
    @Test
    public void test_from_accepts_every_input_shape() {
        assertEquals(2, num("Vector.from(new Vector([1, 2])).length"));
        assertEquals(2, num("Vector.from('#vector(1,2)').at(1)"));
        assertEquals(3, num("Vector.from([1, 2, 3]).length"));
    }

    // from rejects a value that is not a vector at all
    @Test
    public void test_from_rejects_other_values() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Vector.from(42)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Vector.from('not a vector')"));
    }

    // A subclass instance keeps the wrapped value reachable through the prototype accessors
    @Test
    public void test_subclass_wrapping() {
        assertEquals(2, num("class V extends Vector {}; new V([1, 2]).length"));
    }

    // An array-like (non-array) object supplies its components through length + index reads
    @Test
    public void test_from_an_array_like_object() {
        assertEquals(2, num("Vector.from({ length: 2, 0: 5, 1: 6 }).length"));
        assertEquals(6, num("new Vector({ length: 2, 0: 5, 1: 6 }).at(1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Vector.from({ length: 0 })"));
    }

    // A subclass wrapper is unwrapped by both the accessors and the methods
    @Test
    public void test_subclass_receiver_is_unwrapped() {
        assertEquals(16, num("class V extends Vector {}; new V([1, 2]).simHash.length"));
        assertEquals(2, num("class V extends Vector {}; new V([1, 2]).at(1)"));
        assertEquals(2, num("class V extends Vector {}; Vector.from(new V([1, 2])).length"));
    }

    // A foreign receiver is rejected by every prototype accessor and method
    @Test
    public void test_foreign_receiver_is_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(Vector.prototype, 'length').get.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Vector.prototype.at.call({}, 0)"));
    }

    // A component that is not already a number is coerced, and a non-finite one is kept
    @Test
    public void test_component_coercion() {
        assertEquals(3, num("new Vector(['3']).at(0)"));
        assertTrue(bool("new Vector([Infinity]).at(0) === Infinity"));
    }
}
