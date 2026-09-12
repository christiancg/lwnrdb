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

    @Test
    public void test_truncation_stops_at_a_non_configurable_index() {
        final var array = array(4);
        array.setIndexFlags(1, new JsObject.PropertyFlags(true, true, false));
        assertFalse(array.setLength(0));
        assertEquals(2, array.length());
        assertTrue(array.setLength(2));
    }

    @Test
    public void test_shrinking_past_a_non_configurable_sparse_index_is_rejected() {
        assertEquals("[true,100000001]",
                str("const a = []; a[100000000] = 1;"
                        + " Object.defineProperty(a, '100000000', { configurable: false });"
                        + " let threw = false; try { a.length = 0 } catch (e) { threw = e instanceof TypeError }"
                        + " JSON.stringify([threw, a.length])"));
    }

    @Test
    public void test_shrinking_past_a_configurable_sparse_index_removes_it() {
        assertEquals("[true,0,false]",
                str("const a = []; a[100000000] = 1;" + " const before = 100000000 in a; a.length = 0;"
                        + " JSON.stringify([before, a.length, 100000000 in a])"));
    }

    @Test
    public void test_delete_length_always_fails() {
        final var array = array(2);
        assertFalse(array.deleteOwnProperty(new JsString("length")));
        assertEquals(2, array.length());
    }

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

    @Test
    public void test_shrinking_past_a_non_configurable_wide_index_is_rejected() {
        final var array = new JsArray();
        final var wideIndex = 4_294_967_294L;
        array.defineOwnProperty(new JsString(Long.toString(wideIndex)),
                new PropertyDescriptor(new JsNumber(1), null, null, true, true, false));
        assertFalse(array.setLength(2));
        assertEquals(wideIndex + 1, array.length());
    }

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

    @Test
    public void test_delete_symbol_keyed_property() {
        final var array = new JsArray();
        final var symbol = new org.techhouse.simplejs.values.JsSymbol("s");
        array.defineOwnProperty(symbol, PropertyDescriptor.data(new JsNumber(1), JsObject.PropertyFlags.DEFAULT));
        assertTrue(array.hasOwnKey(symbol));
        assertTrue(array.deleteOwnProperty(symbol));
        assertFalse(array.hasOwnKey(symbol));
    }

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

    @Test
    public void test_array_subclass_instance_length_descriptor_and_truncation_end_to_end() {
        assertEquals("true,false,false,true",
                str("class Ar extends Array {}" + " const arr = new Ar('foo', 'bar');"
                        + " const d = Object.getOwnPropertyDescriptor(arr, 'length');" + " arr.length = 1;"
                        + " d.writable + ',' + d.enumerable + ',' + d.configurable + ',' + (arr[1] === undefined)"));
    }
}
