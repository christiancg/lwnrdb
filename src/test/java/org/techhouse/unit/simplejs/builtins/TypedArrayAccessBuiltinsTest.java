package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArrayBuffer;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsTypedArray;

public class TypedArrayAccessBuiltinsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String caught(String expression) {
        return str("let caught = 'none'; try { " + expression + "; } catch (e) { caught = e.name; } caught");
    }

    @Test
    public void test_excessive_length_throws_range_error_without_allocating() {
        assertEquals("RangeError", caught("new ArrayBuffer(9007199254740992)"));
        assertEquals("RangeError", caught("new ArrayBuffer(7 * 1125899906842624)"));
        assertEquals("RangeError", caught("new ArrayBuffer(Infinity)"));
        assertEquals("RangeError", caught("new ArrayBuffer(-1)"));
        assertEquals("RangeError", caught("new Int32Array(9007199254740991)"));
        assertEquals("RangeError", caught("new ArrayBuffer(0).transfer(9007199254740992)"));
    }

    private static final String DETACHED = "const b = new ArrayBuffer(8); const ta = new Int8Array(b); b.transfer(0); ";

    @Test
    public void test_out_of_bounds_view_throws_type_error() {
        final var shrink = "const b = new ArrayBuffer(8, { maxByteLength: 8 }); const ta = new Int8Array(b, 0, 8);"
                + "b.resize(4); ";
        assertEquals("TypeError", caught(shrink + "ta.forEach(function () {})"));
        assertEquals("0", str(shrink + "String(ta.length)"));
        assertEquals("0", str(shrink + "String(ta.byteOffset)"));
        assertEquals("TypeError",
                caught("const b = new ArrayBuffer(8, { maxByteLength: 8 }); const v = new DataView(b, 4, 4);"
                        + "b.resize(2); v.getInt8(0)"));
    }

    @Test
    public void test_set_reads_array_like_and_checks_content_type() {
        assertEquals("1,2,0", str("const ta = new Int8Array(3); ta.set({ length: 2, 0: 1, 1: 2 }); ta.join(',')"));
        assertEquals("0,1,2", str("const ta = new Int8Array(3); ta.set([1, 2], 1); ta.join(',')"));
        assertEquals("RangeError", caught("new Int8Array(2).set([1, 2, 3])"));
        assertEquals("RangeError", caught("new Int8Array(2).set([1], -1)"));
        assertEquals("TypeError", caught("new Int8Array(2).set(new BigInt64Array(1))"));
    }

    @Test
    public void test_non_index_writes_land_as_own_properties() {
        assertEquals("7", str("const ta = new Int8Array(1); ta.tag = 7; String(ta.tag)"));
        assertEquals("undefined", str("const ta = new Int8Array(1); ta[-1] = 7; String(ta[-1])"));
        assertEquals("undefined", str("const ta = new Int8Array(1); ta[1.5] = 7; String(ta[1.5])"));
        assertEquals("5", str("const ta = new Int8Array(1);"
                + "Object.defineProperty(ta, 'v', { get() { return 5; } }); String(ta.v)"));
    }

    @Test
    public void test_canonical_indices_are_own_data_properties() {
        final var base = "const ta = new Int8Array(2); ";
        assertEquals("0,1", str(base + "Object.getOwnPropertyNames(ta).join(',')"));
        assertEquals("true,true,true", str(base + "const d = Object.getOwnPropertyDescriptor(ta, '0');"
                + "[d.writable, d.enumerable, d.configurable].join(',')"));
        assertEquals("0", str(base + "String(Object.getOwnPropertyDescriptor(ta, '0').value)"));
        assertEquals("undefined", str(base + "String(Object.getOwnPropertyDescriptor(ta, '2'))"));
        assertEquals("true", str(base + "String(Object.prototype.hasOwnProperty.call(ta, '1'))"));
        assertEquals("false", str(base + "String(Object.prototype.hasOwnProperty.call(ta, '2'))"));
    }

    @Test
    public void test_delete_of_a_valid_index_is_refused() {
        final var base = "const ta = new Int8Array(2); ";
        assertEquals("false", str(base + "String(Reflect.deleteProperty(ta, '0'))"));
        assertEquals("true", str(base + "String(Reflect.deleteProperty(ta, '2'))"));
        assertEquals("true", str(base + "String(Reflect.deleteProperty(ta, '1.5'))"));
        assertEquals("true", str(base + "String(Reflect.deleteProperty(ta, 'missing'))"));
        assertEquals("true", str(DETACHED + "String(Reflect.deleteProperty(ta, '0'))"));
    }

    @Test
    public void test_out_of_bounds_write_is_a_silent_no_op() {
        final var base = "const ta = new Int8Array(1); ";
        assertEquals("undefined", str(base + "ta[5] = 3; String(ta[5])"));
        assertEquals("false", str(base + "ta[5] = 3; String(Object.prototype.hasOwnProperty.call(ta, '5'))"));
        assertEquals("true", str(base + "String(Reflect.set(ta, '5', 3))"));
        assertEquals("none", caught(DETACHED + "ta[0] = 1"));
        assertEquals("undefined", str(DETACHED + "ta[0] = 1; String(ta[0])"));
    }

    @Test
    public void test_data_view_length_is_one() {
        assertEquals("1", str("String(DataView.length)"));
    }

    @Test
    public void test_data_view_honours_new_target_prototype() {
        assertEquals("true:true", str("""
                function newTarget() {}
                const proto = {};
                newTarget.prototype = proto;
                const sample = Reflect.construct(DataView, [new ArrayBuffer(8), 0], newTarget);
                String(sample.constructor === Object) + ":" + String(Object.getPrototypeOf(sample) === proto);
                """));
    }

    @Test
    public void test_data_view_default_prototype_unaffected() {
        assertTrue(bool("Object.getPrototypeOf(new DataView(new ArrayBuffer(8))) === DataView.prototype"));
    }

    @Test
    public void test_index_searches_are_strict() {
        final var base = "const ta = new Int8Array([1, 2, 3]); ";
        assertEquals("-1", str(base + "String(ta.indexOf('2'))"));
        assertEquals("-1", str(base + "String(ta.lastIndexOf('2'))"));
        assertEquals("1", str(base + "String(ta.indexOf(2))"));
        assertEquals("-1", str(base + "String(ta.lastIndexOf(3, undefined))"));
        assertEquals("2", str(base + "String(ta.lastIndexOf(3))"));
    }

    @Test
    public void test_from_hex_and_set_from_hex() {
        assertEquals("102,111,111", str("Uint8Array.fromHex('666f6f').join(',')"));
        assertEquals("SyntaxError", caught("Uint8Array.fromHex('666')"));
        assertEquals("SyntaxError", caught("Uint8Array.fromHex('66zz')"));
        assertEquals("SyntaxError", caught("new Uint8Array(0).setFromHex('6')"));
        assertEquals("102,0",
                str("const ta = new Uint8Array(2);" + "try { ta.setFromHex('66zz'); } catch (e) {} ta.join(',')"));
        assertEquals("4,2", str("const ta = new Uint8Array(4); const r = ta.setFromHex('66 6f'.replace(' ', ''));"
                + "[r.read, r.written].join(',')"));
    }

    @Test
    public void test_set_from_base64_reports_read_and_written() {
        assertEquals("4,3,102,111,111,255,255", str("const ta = new Uint8Array([255, 255, 255, 255, 255]);"
                + "const r = ta.setFromBase64('Zm9vYmFy');" + "[r.read, r.written].concat(Array.from(ta)).join(',')"));
        assertEquals("8,5", str("const ta = new Uint8Array(5); const r = ta.setFromBase64('Zm9vYmE=');"
                + "[r.read, r.written].join(',')"));
        assertEquals("0,0", str(
                "const ta = new Uint8Array(0); const r = ta.setFromBase64('#');" + "[r.read, r.written].join(',')"));
        assertEquals("TypeError", caught(DETACHED + "new Uint8Array(new ArrayBuffer(0)).setFromBase64(1)"));
    }

    @Test
    public void test_set_from_base64_rechecks_detach_after_options_getter() {
        assertEquals("TypeError,1", str("""
                var target = new Uint8Array([255, 255, 255]);
                var getterCalls = 0;
                var options = {};
                Object.defineProperty(options, 'alphabet', {
                    get: function() {
                        getterCalls += 1;
                        target.buffer.transfer();
                        return 'base64';
                    }
                });
                let caught = 'none';
                try { target.setFromBase64('Zg==', options); } catch (e) { caught = e.name; }
                [caught, getterCalls].join(',')
                """));
    }

    @Test
    public void test_with_validates_the_index_after_coercion() {
        assertEquals("11,22", str("const b = new ArrayBuffer(2, { maxByteLength: 5 }); const ta = new Int8Array(b);"
                + "ta[0] = 11; ta[1] = 22;" + "const grow = { valueOf: function () { b.resize(5); return 123; } };"
                + "ta.with(4, grow).join(',')"));
        assertEquals("RangeError",
                str("const b = new ArrayBuffer(4, { maxByteLength: 4 }); const ta = new Int8Array(b);"
                        + "const shrink = { valueOf: function () { b.resize(1); return 1; } };"
                        + "let name = 'none'; try { ta.with(-1, shrink); } catch (e) { name = e.name; } name"));
    }

    // The rejection of a non-object `constructor` is not asserted here: a property write on an
    // ArrayBuffer is still dropped by the member seam, so there is no way to install one.
    @Test
    public void test_buffer_slice_consults_the_species_constructor() {
        final var base = "const b = new ArrayBuffer(8); ";
        assertEquals("4", str(base + "String(b.slice(0, 4).byteLength)"));
        assertEquals("8", str(base + "String(b.slice(0).byteLength)"));
        assertEquals("0", str(base + "String(b.slice(4, 2).byteLength)"));
    }

    @Test
    public void test_data_view_geometry_rejects_an_out_of_bounds_view() {
        final var base = "const b = new ArrayBuffer(4, { maxByteLength: 5 }); const v = new DataView(b, 1); ";
        assertEquals("1", str(base + "String(v.byteOffset)"));
        assertEquals("1", str(base + "b.resize(1); String(v.byteOffset)"));
        assertEquals("TypeError", caught(base + "b.resize(0); v.byteOffset"));
        assertEquals("TypeError",
                caught("const b = new ArrayBuffer(4); const v = new DataView(b); b.transfer(0); v.byteOffset"));
    }

    @Test
    public void test_canonical_numeric_index_string() {
        assertEquals(-0.0, JsTypedArray.canonicalNumericIndex("-0"));
        assertEquals(1.0, JsTypedArray.canonicalNumericIndex("1"));
        assertEquals(1.5, JsTypedArray.canonicalNumericIndex("1.5"));
        assertEquals(Double.POSITIVE_INFINITY, JsTypedArray.canonicalNumericIndex("Infinity"));
        assertNull(JsTypedArray.canonicalNumericIndex("1.0"));
        assertNull(JsTypedArray.canonicalNumericIndex("+1"));
        assertNull(JsTypedArray.canonicalNumericIndex(" 1"));
        assertNull(JsTypedArray.canonicalNumericIndex("01"));
        assertNull(JsTypedArray.canonicalNumericIndex("tag"));
        assertNull(JsTypedArray.canonicalNumericIndex(""));
    }

    @Test
    public void test_set_exotic_index_never_reaches_a_foreign_receiver() {
        final var typed = new JsTypedArray(JsTypedArray.Kind.INT8, new JsArrayBuffer(2), 0, 2);
        final var other = new JsObject();
        assertTrue(typed.setExoticIndex(new JsString("0"), new JsNumber(7), typed));
        assertEquals(7d, ((JsNumber) typed.getElement(0)).getValue());
        assertTrue(typed.setExoticIndex(new JsString("5"), new JsNumber(7), typed));
        assertTrue(typed.setExoticIndex(new JsString("5"), new JsNumber(7), other));
        assertTrue(typed.setExoticIndex(new JsString("-0"), new JsNumber(7), other));
        assertTrue(typed.setExoticIndex(new JsString("1.5"), new JsNumber(7), other));
        assertFalse(typed.setExoticIndex(new JsString("0"), new JsNumber(7), other));
        assertFalse(typed.setExoticIndex(new JsString("tag"), new JsNumber(7), other));
        assertTrue(typed.isValidIntegerIndex(1));
        assertFalse(typed.isValidIntegerIndex(2));
        assertFalse(typed.isValidIntegerIndex(1.5));
        assertFalse(typed.isValidIntegerIndex(-0.0));
    }

    @Test
    public void test_index_write_coerces_before_validating_the_index() {
        final var thrower = "const t = { valueOf: function () { throw new RangeError('coerced'); } }; "
                + "const s = new Int8Array([1]); ";
        assertEquals("RangeError", caught(thrower + "s['5'] = t"));
        assertEquals("RangeError", caught(thrower + "s['1.1'] = t"));
        assertEquals("RangeError", caught(thrower + "s['-0'] = t"));
        assertEquals("RangeError", caught(thrower + "s['0'] = t"));
    }

    @Test
    public void test_index_write_never_becomes_an_ordinary_property() {
        assertEquals("2undefined",
                str("const s = new Int8Array([1, 2]); s['1.1'] = 9; " + "String(s[1]) + String(s['1.1'])"));
        assertEquals("RangeError",
                caught("const s = new Int8Array([1]);"
                        + "Object.defineProperty(s, 'tag', { set: function () { throw new RangeError('setter'); } });"
                        + "s.tag = 1"));
    }

    @Test
    public void test_index_write_sees_a_buffer_resized_by_the_coercion() {
        assertEquals("1|100",
                str("const rab = new ArrayBuffer(0, { maxByteLength: 1 });" + "const ta = new Int8Array(rab);"
                        + "ta[0] = { valueOf: function () { rab.resize(1); return 100; } };"
                        + "String(ta.length) + '|' + String(ta[0])"));
    }

    @Test
    public void test_receiver_aware_index_write() {
        assertEquals("true", str("const s = new Int8Array([1]); String(Reflect.set(s, '5', 9, s))"));
        assertEquals("RangeError", caught("const s = new Int8Array([1]);"
                + "Reflect.set(s, '5', { valueOf: function () { throw new RangeError('coerced'); } }, s)"));
        assertEquals("true", str("const s = new Int8Array([1]); String(Reflect.set(s, '5', 9, {}))"));
        assertEquals("1|9", str("const s = new Int8Array([1]); const r = {};"
                + "Reflect.set(s, '0', 9, r); String(s[0]) + '|' + String(r[0])"));
    }

    @Test
    public void test_set_big_int_coerces_the_value_first() {
        assertEquals("Test262Error", caught("const v = new DataView(new ArrayBuffer(8));"
                + "v.setBigInt64(100, { valueOf: function () { throw { name: 'Test262Error' }; } })"));
        assertEquals("7", str("const v = new DataView(new ArrayBuffer(8));"
                + "v.setBigInt64(0, { valueOf: function () { return 7n; } }); String(v.getBigInt64(0))"));
    }

    // The [[Prototype]] link used to be silently dropped, leaving the lookup on the intrinsic
    // %TypedArray%.prototype.
    @Test
    public void test_set_prototype_of_is_observable_and_walks_to_it() {
        assertTrue(bool("""
                const a = new Int32Array(1);
                const b = { foo: 1 };
                Object.setPrototypeOf(a, b);
                ("foo" in a) && !("bar" in a)
                """));
    }
}
