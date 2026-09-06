package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
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

    // ValidateAndApplyPropertyDescriptor: a non-writable "length" rejects an explicit
    // writable:true even when the requested value equals the current one - the length-changed check
    // alone is not enough, since the writable-toggle rejection is unconditional.
    @Test
    public void test_writable_true_on_non_writable_length_rejected_even_when_value_unchanged() {
        final var array = array(2);
        array.setLengthWritable(false);
        assertThrows(TypeErrorException.class, () -> array.defineOwnProperty(new JsString("length"),
                new PropertyDescriptor(new JsNumber(2), null, null, true, null, null)));
        assertEquals(2, array.length());
    }

    // The JS-visible counterpart: Reflect.defineProperty reports false (not a thrown TypeError) for
    // the same rejected writable-toggle-on-unchanged-value redefine.
    @Test
    public void test_reflect_define_property_writable_true_on_non_writable_length_returns_false() {
        assertFalse(((JsBoolean) Interpreter
                .run("const a = [1, 2];" + " Object.defineProperty(a, 'length', {writable: false});"
                        + " Reflect.defineProperty(a, 'length', {value: 2, writable: true})"))
                .getValue());
    }

    // ArraySetLength ( A, Desc ) steps 3-4: Desc.[[Value]]'s toString is invoked to compute the new
    // length (no valueOf on the object, so ToPrimitive falls through to it) through the ops-aware
    // coercion Object.defineProperty threads into JsArray via withLengthCoercionOps.
    @Test
    public void test_length_value_object_with_only_tostring_is_coerced_through_ops() {
        assertEquals(2,
                num("const a = []; Object.defineProperty(a, 'length', {value: {toString(){return '2'}}}); a.length"));
    }

    // 15.2.3.6-4-150 / 15.2.3.7-6-a-146: neither toString nor valueOf returns a primitive, so
    // ToPrimitive throws a TypeError only after trying both (in valueOf, toString order).
    @Test
    public void test_length_value_object_with_no_primitive_conversion_throws_after_trying_both() {
        assertEquals("TypeError true true",
                str("let ts = false, vo = false; let name = null;" + " try { Object.defineProperty([], 'length',"
                        + " {value: {toString(){ts=true;return {};}, valueOf(){vo=true;return {};}}}); }"
                        + " catch (e) { name = e.name; }" + " name + ' ' + ts + ' ' + vo"));
    }

    // define-own-prop-length-coercion-order.js: ToUint32(Desc.[[Value]]) and ToNumber(Desc.[[Value]])
    // are two SEPARATE coercions, so a valueOf that flips "length" to non-writable on its second call
    // (skipping the first) is observed by the descriptor validation that runs after both - yielding a
    // TypeError even though the numeric value itself never actually changes.
    @Test
    public void test_length_value_coercion_calls_value_of_twice_and_observes_second_calls_side_effect() {
        final var source = "let calls = 0; const arr = [1, 2];" + " const length = { valueOf(){ calls++;"
                + " if (calls !== 1) { Object.defineProperty(arr, 'length', {writable: false}); }"
                + " return arr.length; } };" + " let name = null;"
                + " try { Object.defineProperty(arr, 'length', {value: length, writable: true}); }"
                + " catch (e) { name = e.name; }" + " name + ' ' + calls";
        assertEquals("TypeError 2", str(source));
    }

    // The same scenario through Reflect.defineProperty: false instead of a thrown TypeError, and the
    // coercion still runs exactly twice.
    @Test
    public void test_reflect_length_value_coercion_calls_value_of_twice_and_returns_false() {
        final var source = "let calls = 0; const arr = [1, 2];" + " const length = { valueOf(){ calls++;"
                + " if (calls !== 1) { Object.defineProperty(arr, 'length', {writable: false}); }"
                + " return arr.length; } };"
                + " const result = Reflect.defineProperty(arr, 'length', {value: length, writable: true});"
                + " result + ' ' + calls";
        assertEquals("false 2", str(source));
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

    // A descriptor like {get: undefined, set: undefined} on an array index is still a genuine
    // accessor property per spec, not a data property - mirrors PropertyTable's own
    // accessor-with-both-sides-undefined fix (values/PropertyTable.java) for the array-index
    // storage. The getter/setter fields must be JsUndefined.getInstance() (present-but-undefined),
    // not raw null (absent), or isAccessorDescriptor() never recognises this as an accessor.
    @Test
    public void test_index_accessor_with_both_sides_undefined_is_a_genuine_accessor() {
        final var array = array(1);
        array.defineOwnProperty(new JsString("0"),
                new PropertyDescriptor(null, JsUndefined.getInstance(), JsUndefined.getInstance(), null, true, true));
        assertTrue(array.hasIndexAccessor(0));
        assertNull(array.getIndexAccessorGetter(0));
        assertNull(array.getIndexAccessorSetter(0));
    }

    // Redefining a non-configurable no-sides accessor index with an identical no-sides descriptor
    // must be accepted (SameValue on both undefined accessor sides), matching the plain-property
    // compatibility check already covered above for a real getter/setter pair.
    @Test
    public void test_redefine_non_configurable_index_accessor_with_both_sides_undefined_is_allowed() {
        final var array = array(1);
        final var descriptor = new PropertyDescriptor(null, JsUndefined.getInstance(), JsUndefined.getInstance(), null,
                false, false);
        array.defineOwnProperty(new JsString("0"), descriptor);
        array.defineOwnProperty(new JsString("0"), descriptor);
        assertTrue(array.hasIndexAccessor(0));
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

    // setWideIndex is the ordinary [[Set]] fast path's counterpart to canonicalArrayIndexWide: an
    // index past Integer.MAX_VALUE cannot live in the int-keyed elements/sparseValues storage, so it
    // is kept as an ordinary named property in `table` instead, while still bumping `length` exactly
    // like the int-keyed set(int) does.
    @Test
    public void test_set_wide_index_stores_as_named_property_and_bumps_length() {
        final var array = new JsArray();
        final var wideIndex = 2_147_483_648L; // 2^31, past Integer.MAX_VALUE
        assertTrue(array.setWideIndex(wideIndex, new JsNumber(42)));
        assertEquals(wideIndex + 1, array.length());
        assertTrue(array.hasProperty(Long.toString(wideIndex)));
        assertEquals(42, ((JsNumber) Objects.requireNonNull(array.getProperty(Long.toString(wideIndex)))).getValue());
    }

    // setWideIndex delegates to the ordinary set(int) path below Integer.MAX_VALUE, rather than ever
    // storing a small index as a named property.
    @Test
    public void test_set_wide_index_below_integer_max_delegates_to_dense_set() {
        final var array = new JsArray();
        assertTrue(array.setWideIndex(5, new JsNumber(1)));
        assertEquals(6, array.length());
        assertEquals(1, ((JsNumber) array.get(5)).getValue());
        assertFalse(array.hasProperty("5"));
    }

    // A frozen array rejects a wide-index write exactly like it rejects an ordinary one.
    @Test
    public void test_set_wide_index_rejected_when_frozen() {
        final var array = new JsArray();
        array.freeze();
        assertFalse(array.setWideIndex(2_147_483_648L, new JsNumber(1)));
    }

    // A non-extensible (but not frozen) array rejects a *new* wide index at or past the current
    // length, mirroring set(int)'s equivalent extensibility check.
    @Test
    public void test_set_wide_index_rejected_when_non_extensible_and_growing() {
        final var array = new JsArray();
        array.preventExtensions();
        assertFalse(array.setWideIndex(2_147_483_648L, new JsNumber(1)));
        assertEquals(0, array.length());
    }

    // A non-writable *existing* wide-indexed property rejects an overwrite, mirroring set(int)'s
    // hasValueAt/writable check for the dense/sparse region.
    @Test
    public void test_set_wide_index_overwrite_rejected_when_not_writable() {
        final var array = new JsArray();
        final var wideIndex = 2_147_483_648L;
        array.defineOwnProperty(new JsString(Long.toString(wideIndex)),
                new PropertyDescriptor(new JsNumber(1), null, null, false, true, true));
        assertFalse(array.setWideIndex(wideIndex, new JsNumber(2)));
        assertEquals(1, ((JsNumber) Objects.requireNonNull(array.getProperty(Long.toString(wideIndex)))).getValue());
    }

    // Writing to an already-covered wide index (one below the current length) does not re-bump
    // length - the "no growth" side of the length-bump check.
    @Test
    public void test_set_wide_index_within_existing_length_does_not_rebump_length() {
        final var array = new JsArray();
        final var lower = 2_147_483_648L;
        final var upper = 2_147_483_700L;
        array.setWideIndex(upper, new JsNumber(1));
        assertEquals(upper + 1, array.length());
        assertTrue(array.setWideIndex(lower, new JsNumber(2)));
        assertEquals(upper + 1, array.length());
    }

    // "length" is always non-configurable, so deleting it must always fail - not silently succeed by
    // falling through the "absent key" path the way an ordinary named property does.
    @Test
    public void test_delete_length_always_fails() {
        final var array = array(2);
        assertFalse(array.deleteOwnProperty(new JsString("length")));
        assertEquals(2, array.length());
    }

    // The JS-visible counterpart: Reflect.deleteProperty on an array's "length" must report false.
    @Test
    public void test_reflect_delete_length_reports_false() {
        assertFalse(((JsBoolean) Interpreter.run("Reflect.deleteProperty([1, 2], 'length')")).getValue());
    }

    // Shrinking length past a wide (> Integer.MAX_VALUE) index deletes it, mirroring the sparse/dense
    // tail removal - the ArraySetLength boundary test this closes (S15.4.5.2_A3_T4).
    @Test
    public void test_shrinking_past_a_wide_index_removes_it() {
        final var array = new JsArray();
        final var wideIndex = 4_294_967_294L; // 2^32 - 2, the largest legal array index
        array.setWideIndex(wideIndex, new JsNumber(1));
        assertTrue(array.setLength(2));
        assertEquals(2, array.length());
        assertFalse(array.hasProperty(Long.toString(wideIndex)));
    }

    // A non-configurable wide index stops the descending truncation walk exactly like a non-
    // configurable sparse/dense one does, leaving length just above it.
    @Test
    public void test_shrinking_past_a_non_configurable_wide_index_is_rejected() {
        final var array = new JsArray();
        final var wideIndex = 4_294_967_294L;
        array.defineOwnProperty(new JsString(Long.toString(wideIndex)),
                new PropertyDescriptor(new JsNumber(1), null, null, true, true, false));
        assertFalse(array.setLength(2));
        assertEquals(wideIndex + 1, array.length());
    }

    // removeWideTailDown's descending walk skips a wide index that is still below the new length
    // (the "continue" branch) while still removing one at or past it, in the same truncation call.
    @Test
    public void test_shrinking_skips_a_surviving_wide_index_but_removes_a_later_one() {
        final var array = new JsArray();
        final var surviving = 2_147_483_700L;
        final var removed = 2_147_483_800L;
        array.setWideIndex(surviving, new JsNumber(1));
        array.setWideIndex(removed, new JsNumber(2));
        assertTrue(array.setLength(surviving + 1));
        assertEquals(surviving + 1, array.length());
        assertTrue(array.hasProperty(Long.toString(surviving)));
        assertFalse(array.hasProperty(Long.toString(removed)));
    }

    // A symbol-keyed delete on an array falls through to the ordinary JsValue path (arrays have no
    // exotic symbol-keyed behaviour), rather than the array-index-specific branches above it.
    @Test
    public void test_delete_symbol_keyed_property() {
        final var array = new JsArray();
        final var symbol = new org.techhouse.simplejs.values.JsSymbol("s");
        array.defineOwnProperty(symbol, PropertyDescriptor.data(new JsNumber(1), JsObject.PropertyFlags.DEFAULT));
        assertTrue(array.hasOwnKey(symbol));
        assertTrue(array.deleteOwnProperty(symbol));
        assertFalse(array.hasOwnKey(symbol));
    }

    // End-to-end through the interpreter: an ordinary assignment past Integer.MAX_VALUE (the array-
    // length fast-path gap the master plan called the "Former hard blocker on 100%") now bumps
    // length exactly like Object.defineProperty already did.
    @Test
    public void test_ordinary_assignment_past_integer_max_value_bumps_length_through_interpreter() {
        assertEquals(2147483649.0, num("const x = []; x[2147483648] = 1; x.length"));
        assertEquals(4294967295.0, num("const x = []; x[2147483648] = 1; x[4294967294] = 1; x.length"));
    }

    // setWideIndex honours frozen/non-extensible exactly like set(int) does: a brand-new wide index
    // (past the current length) cannot be added to a non-extensible array.
    @Test
    public void test_wide_index_write_rejected_on_a_non_extensible_array() {
        final var array = new JsArray();
        array.preventExtensions();
        assertFalse(array.setWideIndex(4_294_967_294L, new JsNumber(1)));
        assertEquals(0, array.length());
    }

    // setWideIndex also honours an existing non-writable named property at that wide index, the same
    // way an ordinary [[Set]] would.
    @Test
    public void test_wide_index_write_rejected_when_existing_property_is_non_writable() {
        final var array = new JsArray();
        final var wideIndex = 4_294_967_294L;
        array.defineOwnProperty(new JsString(Long.toString(wideIndex)),
                new PropertyDescriptor(new JsNumber(1), null, null, false, true, true));
        assertFalse(array.setWideIndex(wideIndex, new JsNumber(2)));
        assertEquals(1, ((JsNumber) Objects.requireNonNull(array.getProperty(Long.toString(wideIndex)))).getValue());
    }

    // removeWideTailDown's descending walk must skip (not touch) a wide key still below the new
    // length rather than rejecting or removing it.
    @Test
    public void test_shrinking_skips_a_wide_key_still_below_the_new_length() {
        final var array = new JsArray();
        final var keep = 5_000_000_000L;
        array.setWideIndex(keep, new JsNumber(1));
        assertTrue(array.setLength(keep + 100));
        assertTrue(array.hasProperty(Long.toString(keep)));
        assertEquals(keep + 100, array.length());
    }

    // %Array.prototype% carries a real own "length" per spec 22.1.3: initial value 0, attributes
    // {writable:true, enumerable:false, configurable:false}.
    @Test
    public void test_array_prototype_has_a_real_own_length() {
        assertEquals(0, num("Array.prototype.length"));
        assertEquals("true,false,false", str("const d = Object.getOwnPropertyDescriptor(Array.prototype, 'length');"
                + " d.writable + ',' + d.enumerable + ',' + d.configurable"));
        assertTrue(((JsBoolean) Interpreter.run("'length' in Object.create(Array.prototype)")).getValue());
    }

    // The regression Wave 6's Stream V hit: `class A extends Array` wraps a real JsArray as the
    // instance's primitive, and MemberEvaluator.getObjectMember must answer "length" from that
    // primitive unconditionally - never from whatever %Array.prototype% itself carries (which is now
    // a real own "length" of 0 per the test above) - or every subclass instance's length would be
    // shadowed down to 0.
    @Test
    public void test_array_subclass_instance_length_is_not_shadowed_by_array_prototype_length() {
        assertEquals(3, num("class A extends Array {} new A(1, 2, 3).length"));
        assertEquals(0, num("Array.prototype.length"));
        assertEquals(4, num("class A extends Array {} const a = new A(1, 2, 3); a.push(4); a.length"));
    }

    // Symmetric with the read side: setObjectMember must apply the wrapped primitive's ArraySetLength
    // directly (truncating elements) rather than either (a) being deflected by %Array.prototype%'s own
    // writable "length" data property found while walking the chain, or (b) falling through to
    // creating an ordinary "length" property on the wrapper object that never touches the real array.
    @Test
    public void test_array_subclass_instance_length_assignment_truncates_the_real_array() {
        assertEquals("[\"foo\",true]",
                str("class Ar extends Array {}" + " const arr = new Ar('foo', 'bar'); arr.length = 1;"
                        + " JSON.stringify([arr[0], arr[1] === undefined])"));
        assertEquals(1, num("class A extends Array {} const a = new A(1, 2, 3); a.length = 1; a.length"));
    }

    // Object.getOwnPropertyDescriptor(subclassInstance, 'length') already delegates to the wrapped
    // primitive (ObjectBuiltins, outside this stream's scope) and reports the real array's flags -
    // the language/statements/class/subclass/builtin-objects/Array/length.js scenario end to end.
    @Test
    public void test_array_subclass_instance_length_descriptor_and_truncation_end_to_end() {
        assertEquals("true,false,false,true",
                str("class Ar extends Array {}" + " const arr = new Ar('foo', 'bar');"
                        + " const d = Object.getOwnPropertyDescriptor(arr, 'length');" + " arr.length = 1;"
                        + " d.writable + ',' + d.enumerable + ',' + d.configurable + ',' + (arr[1] === undefined)"));
    }
}
