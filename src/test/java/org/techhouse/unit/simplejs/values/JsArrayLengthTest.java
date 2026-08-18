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
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.PropertyDescriptor;

public class JsArrayLengthTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run(
                "const a = [1, 2]; Object.defineProperty(a, 'length', {writable: false}); let threw = false; try { a.length = 2; } catch (e) { threw = e instanceof TypeError; } threw"))
                .getValue();
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

    // ArrayDefineOwnProperty step 4.d: an index at or past the current length can't be added while
    // "length" is non-writable, even though the same index redefinition would be fine on its own.
    @Test
    public void test_define_new_index_past_length_rejected_when_length_not_writable() {
        final var array = array(3);
        array.setLengthWritable(false);
        assertThrows(TypeErrorException.class, () -> array.defineOwnProperty(new JsString("3"),
                new PropertyDescriptor(new JsNumber(9), null, null, null, null, null)));
        assertEquals(3, array.length());
    }

    // A redefine of an existing non-configurable accessor index is compatible (and so allowed) when
    // it only repeats the current getter/setter identity, matching OrdinaryProperties' shared
    // ValidateAndApplyPropertyDescriptor - the array-index path used to reject this unconditionally.
    @Test
    public void test_redefine_non_configurable_accessor_index_with_same_identity_is_allowed() {
        final var array = array(1);
        final var getter = new JsNativeFunction("getter", (_, _) -> JsUndefined.getInstance());
        array.defineOwnProperty(new JsString("0"), new PropertyDescriptor(null, getter, null, null, false, false));
        array.defineOwnProperty(new JsString("0"), new PropertyDescriptor(null, getter, null, null, null, null));
        assertTrue(array.hasIndexAccessor(0));
        assertEquals(getter, array.getIndexAccessorGetter(0));
    }

    // A generic descriptor (only enumerable/configurable, no get/set/value/writable) over an existing
    // accessor index must not clobber the accessor - it only ever touches the flags.
    @Test
    public void test_generic_redefine_over_accessor_index_preserves_the_accessor() {
        final var array = array(1);
        final var getter = new JsNativeFunction("getter", (_, _) -> JsUndefined.getInstance());
        array.defineOwnProperty(new JsString("0"), new PropertyDescriptor(null, getter, null, null, true, true));
        array.defineOwnProperty(new JsString("0"), new PropertyDescriptor(null, null, null, null, false, null));
        assertTrue(array.hasIndexAccessor(0));
        assertEquals(getter, array.getIndexAccessorGetter(0));
        assertFalse(array.getIndexFlags(0).enumerable());
    }

    // Documented boundary decision (see Hard blocker on 100%, plans/simplejs-test262-100-percent.md):
    // JsArray stores one dense slot per index and refuses to represent the spec-legal length range up
    // to 2^32-1, since a length that large is otherwise legal without ever allocating a matching
    // number of real elements. Closing this needs a sparse/virtual-length representation that spans
    // call sites outside JsArray (ArrayBuiltins' own int-capped newArray/createDataPropertyOrThrow,
    // and InterpreterUtils.arrayIndex's Integer-typed index parsing consumed by MemberEvaluator/
    // StatementEvaluator/Interpreter) - deliberately not attempted this pass. This test pins the
    // current, intentional restriction so a future change is a conscious decision, not silent drift.
    @Test
    public void test_length_beyond_dense_capacity_is_a_documented_range_error() {
        final var array = new JsArray();
        assertThrows(RangeErrorException.class, () -> array.setLength(Integer.MAX_VALUE));
    }
}
