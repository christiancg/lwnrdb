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

public class GeoBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("class G extends Geo {}; new G(1, 2) instanceof G")).getValue();
    }

    // The constructor takes latitude then longitude
    @Test
    public void test_construction() {
        assertEquals(41.5, num("new Geo(41.5, -3.25).lat"));
        assertEquals(-3.25, num("new Geo(41.5, -3.25).lng"));
    }

    // A missing argument coerces to NaN, which is out of range
    @Test
    public void test_missing_arguments_are_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Geo()"));
    }

    // String coercion is the EJson wire form the storage layer already understands
    @Test
    public void test_string_coercion_is_the_wire_form() {
        assertEquals("#geo(1.0,2.0)", str("String(new Geo(1, 2))"));
        assertEquals("#geo(1.0,2.0)", str("new Geo(1, 2).toString()"));
        assertEquals("#geo(1.0,2.0)", str("new Geo(1, 2).toJSON()"));
    }

    // geoHash exposes the same spatially-clustered ordering key the geo index uses
    @Test
    public void test_geo_hash_accessor() {
        assertEquals(12, num("new Geo(41.5, -3.25).geoHash.length"));
    }

    // typeof is "object" and the brand comes from the prototype's toStringTag
    @Test
    public void test_type_and_brand() {
        assertEquals("object", str("typeof new Geo(1, 2)"));
        assertEquals("[object Geo]", str("Object.prototype.toString.call(new Geo(1, 2))"));
    }

    // Calling the constructor without new throws
    @Test
    public void test_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Geo(1, 2)"));
    }

    // Latitude and longitude are range-checked at the poles and the antimeridian
    @Test
    public void test_range_checks() {
        assertEquals(90, num("new Geo(90, 180).lat"));
        assertEquals(-180, num("new Geo(-90, -180).lng"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Geo(90.1, 0)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Geo(0, 180.1)"));
    }

    // from accepts an instance, the wire string and a {lat, lng} object
    @Test
    public void test_from_accepts_every_input_shape() {
        assertEquals(1, num("Geo.from(new Geo(1, 2)).lat"));
        assertEquals(2, num("Geo.from('#geo(1,2)').lng"));
        assertEquals(1, num("Geo.from({ lat: 1, lng: 2 }).lat"));
    }

    // from rejects a value that is not a geo at all
    @Test
    public void test_from_rejects_other_values() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Geo.from(42)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Geo.from('not a geo')"));
    }

    // A subclass instance keeps the wrapped value reachable through the prototype accessors
    @Test
    public void test_subclass_wrapping() {
        assertEquals(1, num("class G extends Geo {}; new G(1, 2).lat"));
        assertTrue(bool());
    }

    // A subclass wrapper is unwrapped by both the accessors and the methods
    @Test
    public void test_subclass_receiver_is_unwrapped() {
        assertEquals(12, num("class G extends Geo {}; new G(1, 2).geoHash.length"));
        assertEquals("#geo(1.0,2.0)", str("class G extends Geo {}; new G(1, 2).toString()"));
        assertEquals(1, num("class G extends Geo {}; Geo.from(new G(1, 2)).lat"));
    }

    // A foreign receiver is rejected by every prototype accessor and method
    @Test
    public void test_foreign_receiver_is_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(Geo.prototype, 'lat').get.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Geo.prototype.toJSON.call({})"));
    }

    // A {lat, lng} bag is still range-checked, and a missing member reads as NaN
    @Test
    public void test_from_object_is_range_checked() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Geo.from({ lat: 91, lng: 0 })"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Geo.from({})"));
    }
}
