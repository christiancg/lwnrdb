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

public class DbDateTimeBuiltinsTest {
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
    public void test_constructor_defaults() {
        assertEquals(1970, num("new DbDateTime().year"));
        assertEquals(1, num("new DbDateTime().month"));
        assertEquals(1, num("new DbDateTime().day"));
        assertEquals(0, num("new DbDateTime().hour"));
    }

    @Test
    public void test_all_argument_construction() {
        final var expression = "new DbDateTime(2024, 3, 17, 9, 30, 15)";
        assertEquals(2024, num(expression + ".year"));
        assertEquals(3, num(expression + ".month"));
        assertEquals(17, num(expression + ".day"));
        assertEquals(9, num(expression + ".hour"));
        assertEquals(30, num(expression + ".minute"));
        assertEquals(15, num(expression + ".second"));
    }

    @Test
    public void test_out_of_range_components() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbDateTime(2024, 13, 1)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbDateTime(2023, 2, 29)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbDateTime(2024, 1, 1, 23, 59, 60)"));
    }

    @Test
    public void test_string_coercion_is_the_wire_form() {
        assertEquals("#datetime(2024-03-17T09:30:15)", str("String(new DbDateTime(2024, 3, 17, 9, 30, 15))"));
        assertEquals("#datetime(2024-03-17T09:30:15)", str("new DbDateTime(2024, 3, 17, 9, 30, 15).toJSON()"));
    }

    @Test
    public void test_type_and_brand() {
        assertEquals("object", str("typeof new DbDateTime()"));
        assertEquals("[object DbDateTime]", str("Object.prototype.toString.call(new DbDateTime())"));
    }

    @Test
    public void test_requires_new() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DbDateTime(2024, 1, 1)"));
    }

    @Test
    public void test_from_accepts_every_input_shape() {
        assertEquals(2024, num("DbDateTime.from(new DbDateTime(2024, 1, 1)).year"));
        assertEquals(2024, num("DbDateTime.from('#datetime(2024-03-17T09:30:15)').year"));
        assertEquals(17, num("DbDateTime.from('2024-03-17T09:30:15').day"));
        assertEquals(3, num("DbDateTime.from({ year: 2024, month: 3, day: 17 }).month"));
        assertEquals(2024, num("DbDateTime.from(Temporal.PlainDateTime.from('2024-03-17T09:30:15')).year"));
        assertEquals(2024, num("DbDateTime.from(Temporal.PlainDate.from('2024-03-17')).year"));
    }

    @Test
    public void test_from_rejects_other_values() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DbDateTime.from(42)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("DbDateTime.from('not a date')"));
    }

    @Test
    public void test_to_temporal() {
        assertTrue(bool("new DbDateTime(2024, 3, 17).toTemporal() instanceof Temporal.PlainDateTime"));
        assertEquals("2024-03-18T00:00:00",
                str("new DbDateTime(2024, 3, 17).toTemporal().add({ days: 1 }).toString()"));
    }

    @Test
    public void test_subclass_wrapping() {
        assertEquals(2024, num("class D extends DbDateTime {}; new D(2024, 1, 1).year"));
    }

    @Test
    public void test_subclass_receiver_is_unwrapped() {
        assertEquals("object", str("class D extends DbDateTime {}; typeof new D(2024, 3, 17).toTemporal()"));
        assertTrue(bool("class D extends DbDateTime {}; DbDateTime.from(new D(2024, 3, 17)) instanceof DbDateTime"));
    }

    @Test
    public void test_foreign_receiver_is_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.getOwnPropertyDescriptor(DbDateTime.prototype, 'year').get.call({})"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("DbDateTime.prototype.toJSON.call({})"));
    }

    @Test
    public void test_non_finite_components_are_rejected() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DbDateTime(NaN)"));
        assertThrows(RangeErrorException.class, () -> Interpreter.run("DbDateTime.from({ year: Infinity })"));
    }
}
