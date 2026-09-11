package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.PropertyDescriptor;
import org.techhouse.test.JsEval;

public class JsArrayLengthShrinkTest {

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static JsArray array(int elements) {
        final var array = new JsArray();
        for (var i = 0; i < elements; i++) {
            array.push(new JsNumber(i));
        }
        return array;
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

    // removeSparseTailDown's success path: a *configurable* sparse index is dropped outright (not
    // merely rejected) when the shrink walks past it, unlike the non-configurable case above.
    @Test
    public void test_shrinking_past_a_configurable_sparse_index_removes_it() {
        assertEquals("[true,0,false]",
                str("const a = []; a[100000000] = 1;" + " const before = 100000000 in a; a.length = 0;"
                        + " JSON.stringify([before, a.length, 100000000 in a])"));
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

    // Symmetric with the read side: setObjectMember must apply the wrapped primitive's ArraySetLength
    // directly (truncating elements) rather than either (a) being deflected by %Array.prototype%'s own
    // writable "length" data property found while walking the chain, or (b) falling through to
    // creating an ordinary "length" property on the wrapper object that never touches the real array.
    @Test
    public void test_array_subclass_instance_length_assignment_truncates_the_real_array() {
        assertEquals("[\"foo\",true]",
                str("class Ar extends Array {}" + " const arr = new Ar('foo', 'bar'); arr.length = 1;"
                        + " JSON.stringify([arr[0], arr[1] === undefined])"));
        assertEquals(1, JsEval.num("class A extends Array {} const a = new A(1, 2, 3); a.length = 1; a.length"));
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
