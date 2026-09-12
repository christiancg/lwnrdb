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

    @Test
    public void test_construction() {
        assertEquals(41.5, num("new Geo(41.5, -3.25).lat"));
        assertEquals(-3.25, num("new Geo(41.5, -3.25).lng"));
    }

    @Test
    public void test_missing_arguments_are_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Geo()"));
    }

    @Test
    public void test_string_coercion_is_the_wire_form() {
        assertEquals("#geo(1.0,2.0)", str("String(new Geo(1, 2))"));
        assertEquals("#geo(1.0,2.0)", str("new Geo(1, 2).toString()"));
        assertEquals("#geo(1.0,2.0)", str("new Geo(1, 2).toJSON()"));
    }

    @Test
    public void test_geo_hash_accessor() {
        assertEquals(12, num("new Geo(41.5, -3.25).geoHash.length"));
    }

    @Test
    public void test_type_and_brand() {
        assertEquals("object", str("typeof new Geo(1, 2)"));
        assertEquals("[object Geo]", str("Object.prototype.toString.call(new Geo(1, 2))"));
    }

    @Test
    public void test_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Geo(1, 2)"));
    }

    @Test
    public void test_range_checks() {
        assertEquals(90, num("new Geo(90, 180).lat"));
        assertEquals(-180, num("new Geo(-90, -180).lng"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Geo(90.1, 0)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Geo(0, 180.1)"));
    }

    @Test
    public void test_from_accepts_every_input_shape() {
        assertEquals(1, num("Geo.from(new Geo(1, 2)).lat"));
        assertEquals(2, num("Geo.from('#geo(1,2)').lng"));
        assertEquals(1, num("Geo.from({ lat: 1, lng: 2 }).lat"));
    }

    @Test
    public void test_from_rejects_other_values() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Geo.from(42)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Geo.from('not a geo')"));
    }

    @Test
    public void test_subclass_wrapping() {
        assertEquals(1, num("class G extends Geo {}; new G(1, 2).lat"));
        assertTrue(bool());
    }

    @Test
    public void test_subclass_receiver_is_unwrapped() {
        assertEquals(12, num("class G extends Geo {}; new G(1, 2).geoHash.length"));
        assertEquals("#geo(1.0,2.0)", str("class G extends Geo {}; new G(1, 2).toString()"));
        assertEquals(1, num("class G extends Geo {}; Geo.from(new G(1, 2)).lat"));
    }

    @Test
    public void test_foreign_receiver_is_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(Geo.prototype, 'lat').get.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Geo.prototype.toJSON.call({})"));
    }

    @Test
    public void test_from_object_is_range_checked() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Geo.from({ lat: 91, lng: 0 })"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("Geo.from({})"));
    }
}
