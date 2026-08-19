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

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
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

    // Formerly a documented restriction (see Hard blocker on 100%,
    // plans/simplejs-test262-100-percent.md): JsArray used to store one dense slot per index and
    // refused any length beyond MAX_DENSE_LENGTH. It now backs an index at or past that cap with a
    // sparse map instead, so a length up to the spec's own 2^32-1 ceiling (JsArray.MAX_ARRAY_LENGTH)
    // is representable without allocating a matching number of real elements.
    @Test
    public void test_length_beyond_dense_capacity_now_succeeds_via_the_sparse_representation() {
        final var array = new JsArray();
        assertTrue(array.setLength(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE, array.length());
        assertTrue(array.setLength(JsArray.MAX_ARRAY_LENGTH));
        assertEquals(JsArray.MAX_ARRAY_LENGTH, array.length());
    }

    // A read/write at an index at or past the dense cap round-trips through the sparse overflow map,
    // and bumps length exactly the way a dense write always has.
    @Test
    public void test_sparse_index_set_and_get_round_trip_and_bump_length() {
        final var array = new JsArray();
        final var sparseIndex = 1 << 25; // past MAX_DENSE_LENGTH (1 << 24)
        assertTrue(array.set(sparseIndex, new JsNumber(7)));
        assertEquals(sparseIndex + 1L, array.length());
        assertEquals(7, ((JsNumber) array.get(sparseIndex)).getValue());
        assertFalse(array.isHole(sparseIndex));
        assertTrue(array.isHole(sparseIndex - 1)); // never written, still absent
    }

    // Object.defineProperty on a numeric key past Integer.MAX_VALUE (canonicalArrayIndexWide's whole
    // reason to exist - InterpreterUtils.arrayIndex overflows well before the spec's own ceiling)
    // still recognises it as an array index for the purpose of bumping "length", even though the
    // value itself is stored as an ordinary named property (no int-keyed fast path can address it).
    @Test
    public void test_define_property_past_integer_range_bumps_length() {
        final var array = new JsArray();
        final var hugeIndex = "4294967294"; // 2^32 - 2, the largest legal array index
        array.defineOwnProperty(new JsString(hugeIndex),
                new PropertyDescriptor(new JsNumber(100), null, null, true, true, true));
        assertEquals(JsArray.MAX_ARRAY_LENGTH, array.length());
        assertTrue(array.hasProperty(hugeIndex));
    }

    // requireArrayLength's upper-bound check: a length past the spec's own ceiling is still a
    // RangeError even now that the dense-cap-shaped RangeError is gone.
    @Test
    public void test_define_length_past_spec_ceiling_is_still_a_range_error() {
        final var array = new JsArray();
        assertThrows(RangeErrorException.class, () -> array.defineOwnProperty(new JsString("length"),
                new PropertyDescriptor(new JsNumber(JsArray.MAX_ARRAY_LENGTH + 1), null, null, null, null, null)));
    }

    // getElements() returns a view, not the bare backing list: ExpressionEvaluator's array-literal
    // spread (`[...x]`) mutates the target array's list directly via this method, bypassing push()/
    // set() entirely, so the view's add() must keep `length` in sync itself or a spread result
    // silently reports length 0 despite holding the right elements (regression coverage for that gap).
    @Test
    public void test_elements_view_mutation_keeps_length_in_sync() {
        final var array = new JsArray();
        final var elements = array.getElements();
        elements.add(new JsNumber(1));
        elements.add(new JsNumber(2));
        assertEquals(2, elements.size());
        assertEquals(2, array.length());
        assertEquals(1, ((JsNumber) array.get(0)).getValue());
        assertEquals(2, ((JsNumber) array.get(1)).getValue());
    }

    // End-to-end through the interpreter: a spread of a small array must still report the right
    // length (the exact regression the ElementsView wrapper exists to prevent).
    @Test
    public void test_spread_result_reports_correct_length_through_the_interpreter() {
        assertEquals(3, num("const a = ['x','y','z']; const b = [...a]; b.length"));
    }

    // ArraySetLength's descending walk (removeSparseTailDown) stops at the first non-configurable
    // sparse index and leaves length just above it, mirroring the dense-region behaviour.
    @Test
    public void test_shrinking_past_a_non_configurable_sparse_index_is_rejected() {
        assertEquals("[true,100000001]",
                str("const a = []; a[100000000] = 1;"
                        + " Object.defineProperty(a, '100000000', { configurable: false });"
                        + " let threw = false; try { a.length = 0 } catch (e) { threw = e instanceof TypeError }"
                        + " JSON.stringify([threw, a.length])"));
    }

    // ownPropertyKeys appends sparse indices, sorted, after the dense prefix - so Object.keys sees
    // them in ascending canonical-index order even though they were assigned out of order.
    @Test
    public void test_object_keys_orders_a_sparse_index_after_the_dense_prefix() {
        assertEquals("1,50000000", str("const a = []; a[50000000] = 'x'; a[1] = 'y'; Object.keys(a).join(',')"));
    }

    // defineProperty can install/redefine an accessor at a sparse index exactly like a dense one -
    // IndexSlot.defineAccessor, then clearGetter/clearSetter when it is redefined as a data property.
    @Test
    public void test_accessor_at_a_sparse_index_reads_writes_and_redefines_as_data() {
        assertEquals(4, num("const a = []; let stored;"
                + " Object.defineProperty(a, '50000000', { get() { return 42 }, set(v) { stored = v }, configurable: true });"
                + " const first = a[50000000]; a[50000000] = 99;"
                + " Object.defineProperty(a, '50000000', { value: 7, configurable: true });"
                + " const d = Object.getOwnPropertyDescriptor(a, '50000000');"
                + " [first === 42, stored === 99, d.value === 7, d.get === undefined].filter(Boolean).length"));
    }

    // The ElementsView wrapper keeps `length` in sync for set()/remove(), not just add().
    @Test
    public void test_elements_view_set_and_remove_keep_length_in_sync() {
        final var array = new JsArray();
        final var elements = array.getElements();
        elements.add(new JsNumber(1));
        elements.add(new JsNumber(2));
        elements.set(0, new JsNumber(9));
        assertEquals(2, array.length());
        assertEquals(9, ((JsNumber) array.get(0)).getValue());
        elements.remove(1);
        assertEquals(1, elements.size());
        assertEquals(1, array.length());
    }

    // removeSparseTailDown's success path: a *configurable* sparse index is dropped outright (not
    // merely rejected) when the shrink walks past it, unlike the non-configurable case above.
    @Test
    public void test_shrinking_past_a_configurable_sparse_index_removes_it() {
        assertEquals("[true,0,false]",
                str("const a = []; a[100000000] = 1;" + " const before = 100000000 in a; a.length = 0;"
                        + " JSON.stringify([before, a.length, 100000000 in a])"));
    }

    // delete on a sparse (>= MAX_DENSE_LENGTH) index removes it from the overflow map, not just the
    // dense elements list clearIndexToHole otherwise targets.
    @Test
    public void test_deleting_a_sparse_index_removes_it_from_the_overflow_map() {
        assertEquals("[true,false]",
                str("const a = []; a[100000000] = 1; const before = 100000000 in a; delete a[100000000];"
                        + " JSON.stringify([before, 100000000 in a])"));
    }

    // isFrozen is vacuously true for a non-extensible array with nothing to be non-frozen: no dense
    // elements and no sparse overflow entries either (a higher-level check, not exercised here,
    // additionally requires "length" itself to be non-writable for the JS-visible Object.isFrozen).
    @Test
    public void test_is_frozen_is_vacuously_true_for_an_empty_non_extensible_array() {
        final var array = new JsArray();
        array.preventExtensions();
        assertTrue(array.isFrozen());
    }

    // ownPropertyKeys (the Java-level source Object.getOwnPropertyNames/keys draws from) merges a
    // sparse index into the key list, sorted, after the dense prefix.
    @Test
    public void test_own_property_keys_includes_a_sorted_sparse_index() {
        final var array = new JsArray();
        array.push(new JsNumber(1));
        array.set(50_000_000, new JsNumber(2));
        final var keys = array.ownPropertyKeys();
        assertEquals("0", ((JsString) keys.get(0)).getValue());
        assertEquals("50000000", ((JsString) keys.get(1)).getValue());
        assertEquals("length", ((JsString) keys.get(2)).getValue());
    }

}
