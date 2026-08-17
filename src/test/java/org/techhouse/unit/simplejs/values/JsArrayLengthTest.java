package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.PropertyDescriptor;

public class JsArrayLengthTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("const a = [1, 2]; Object.defineProperty(a, 'length', {writable: false}); let threw = false; try { a.length = 2; } catch (e) { threw = e instanceof TypeError; } threw")).getValue();
    }

    private static JsArray array(int elements) {
        final var array = new JsArray();
        for (var i = 0; i < elements; i++) {
            array.push(new JsNumber(i));
        }
        return array;
    }

    // A non-writable length rejects every [[Set]], including one that would not change the value
    @Test
    public void test_non_writable_length_rejects_any_set() {
        final var array = array(2);
        array.setLengthWritable(false);
        assertFalse(array.setLength(2));
        assertFalse(array.setLength(1));
        assertEquals(2, array.length());
    }

    // Truncation walks down from the tail and stops at the first non-configurable index
    @Test
    public void test_truncation_stops_at_a_non_configurable_index() {
        final var array = array(4);
        array.setIndexFlags(1, new JsObject.PropertyFlags(true, true, false));
        assertFalse(array.setLength(0));
        assertEquals(2, array.length());
        assertTrue(array.setLength(2));
    }

    // The define path applies the writable attribute even when the truncation it asked for failed
    @Test
    public void test_define_length_applies_writable_before_reporting_failure() {
        final var array = array(3);
        array.setIndexFlags(0, new JsObject.PropertyFlags(true, true, false));
        assertThrows(TypeErrorException.class, () -> array.defineOwnProperty(new JsString("length"),
                new PropertyDescriptor(new JsNumber(0), null, null, false, null, null)));
        assertEquals(1, array.length());
        assertFalse(array.setLength(1));
    }

    // ArraySetLength coerces and range-checks the value before validating the descriptor
    @Test
    public void test_out_of_range_length_is_a_range_error_before_validation() {
        final var array = array(1);
        assertThrows(RangeErrorException.class, () -> array.defineOwnProperty(new JsString("length"),
                new PropertyDescriptor(new JsNumber(-1), null, null, null, null, true)));
        assertThrows(RangeErrorException.class, () -> array.defineOwnProperty(new JsString("length"),
                new PropertyDescriptor(new JsNumber(Double.NaN), null, null, null, true, null)));
    }

    // "length" exists from creation, so it precedes any later named key in the own-key order
    @Test
    public void test_length_precedes_later_named_keys() {
        assertEquals("0,length,foo",
                Interpreter.run(
                        "const a = [1]; a.foo = 2; Object.getOwnPropertyNames(a).join(',')") instanceof JsString text
                                ? text.getValue()
                                : "");
    }

    // The same length semantics through the interpreter: a rejected write is a TypeError
    @Test
    public void test_length_semantics_through_the_interpreter() {
        assertEquals(2, num("const a = [1, 2]; Object.defineProperty(a, 'length', {writable: false});"
                + " try { a.length = 5; } catch (e) {} a.length"));
        assertTrue(bool());
        assertEquals(3,
                num("const a = [1, 2, 3, 4];" + " Object.defineProperty(a, '2', {value: 3, configurable: false});"
                        + " try { a.length = 0; } catch (e) {} a.length"));
    }
}
