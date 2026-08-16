package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

public class TypedArrayBuiltinsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String caught(String expression) {
        return str("let caught = 'none'; try { " + expression + "; } catch (e) { caught = e.name; } caught");
    }

    // ToIndex rejects a length no Data Block could hold instead of attempting the allocation
    @Test
    public void test_excessive_length_throws_range_error_without_allocating() {
        assertEquals("RangeError", caught("new ArrayBuffer(9007199254740992)"));
        assertEquals("RangeError", caught("new ArrayBuffer(7 * 1125899906842624)"));
        assertEquals("RangeError", caught("new ArrayBuffer(Infinity)"));
        assertEquals("RangeError", caught("new ArrayBuffer(-1)"));
        assertEquals("RangeError", caught("new Int32Array(9007199254740991)"));
        assertEquals("RangeError", caught("new ArrayBuffer(0).transfer(9007199254740992)"));
    }

    // an in-range allocation still works, and an absent/undefined length is zero
    @Test
    public void test_ordinary_lengths_still_allocate() {
        assertEquals("8", str("String(new ArrayBuffer(8).byteLength)"));
        assertEquals("0", str("String(new ArrayBuffer().byteLength)"));
        assertEquals("0", str("String(new ArrayBuffer(undefined).byteLength)"));
        assertEquals("4", str("String(new Int32Array(1).byteLength)"));
    }

    private static final String DETACHED = "const b = new ArrayBuffer(8); const ta = new Int8Array(b); b.transfer(0); ";

    // ValidateTypedArray on entry: a detached buffer is a TypeError, not a silent empty result
    @Test
    public void test_methods_throw_on_detached_buffer() {
        assertEquals("TypeError", caught(DETACHED + "ta.forEach(function () {})"));
        assertEquals("TypeError", caught(DETACHED + "ta.map(function (x) { return x; })"));
        assertEquals("TypeError", caught(DETACHED + "ta.filter(function () { return true; })"));
        assertEquals("TypeError", caught(DETACHED + "ta.slice(0)"));
        assertEquals("TypeError", caught(DETACHED + "ta.fill(1)"));
        assertEquals("TypeError", caught(DETACHED + "ta.sort()"));
        assertEquals("TypeError", caught(DETACHED + "ta.join(',')"));
        assertEquals("TypeError", caught(DETACHED + "ta.values()"));
        assertEquals("TypeError", caught(DETACHED + "ta.set([1])"));
        // subarray is the one method the spec deliberately leaves unvalidated
        assertEquals("none", caught(DETACHED + "ta.subarray(0)"));
    }

    // a callback or coercion that detaches mid-call is observed rather than silently tolerated: the
    // iteration length is frozen up front, reads past the detachment yield undefined, and the
    // methods that re-validate after their arguments are coerced throw
    @Test
    public void test_methods_revalidate_after_callback_detaches() {
        assertEquals("1,,,", str("const b = new ArrayBuffer(4); const ta = new Int8Array(b); ta[0] = 1;"
                + "const seen = [];"
                + "ta.forEach(function (x, i) { if (i === 0) { b.transfer(0); } seen.push(x); }); seen.join(',')"));
        assertEquals("TypeError", caught("const b = new ArrayBuffer(4); const ta = new Int8Array(b);"
                + "ta.fill({ valueOf: function () { b.transfer(0); return 1; } })"));
        assertEquals("TypeError", caught("const b = new ArrayBuffer(4); const ta = new Int8Array(b);"
                + "ta.copyWithin(0, { valueOf: function () { b.transfer(0); return 1; } })"));
    }

    // a fixed-length view over a shrunk resizable buffer is out of bounds, not merely shorter
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

    // a length-tracking view just gets shorter or longer with its buffer
    @Test
    public void test_length_tracking_view_follows_resize() {
        final var base = "const b = new ArrayBuffer(4, { maxByteLength: 8 }); const ta = new Int8Array(b); ";
        assertEquals("4", str(base + "String(ta.length)"));
        assertEquals("8", str(base + "b.resize(8); String(ta.length)"));
        assertEquals("2", str(base + "b.resize(2); String(ta.length)"));
        assertEquals("0,0", str(base + "b.resize(2); ta.join(',')"));
    }

    // argument coercion runs a user valueOf instead of stringifying the object to NaN
    @Test
    public void test_arguments_coerce_through_value_of() {
        assertEquals("0,0,9,9", str(
                "const ta = new Int8Array(4);" + "ta.fill(9, { valueOf: function () { return 2; } }); ta.join(',')"));
        assertEquals("2", str("const ta = new Int8Array([1, 2, 3]);"
                + "String(ta.indexOf(3, { valueOf: function () { return 1; } }))"));
        assertEquals("5", str("const v = new DataView(new ArrayBuffer(8));"
                + "v.setInt8({ valueOf: function () { return 3; } }, 5); String(v.getInt8(3))"));
        assertEquals("0,7", str(
                "const ta = new Int8Array(2);" + "ta.set([{ valueOf: function () { return 7; } }], 1); ta.join(',')"));
    }

    // the second argument to every callback-taking method is the callback's `this`
    @Test
    public void test_callbacks_honour_this_arg() {
        final var host = "const host = { mark: 42 }; const ta = new Int8Array([1, 2]); ";
        assertEquals("42,42", str(
                host + "const seen = [];" + "ta.forEach(function () { seen.push(this.mark); }, host); seen.join(',')"));
        assertEquals("42", str(host + "String(ta.map(function () { return this.mark; }, host)[0])"));
        assertEquals("2", str(host + "String(ta.filter(function () { return this.mark === 42; }, host).length)"));
        assertEquals("1", str(host + "String(ta.find(function () { return this.mark === 42; }, host))"));
    }

    // SpeciesConstructor with no reachable @@species falls back to the exemplar's own kind, and the
    // derived view keeps the source's buffer for subarray but not for the copying methods.
    // A non-default species is not exercised here because neither a typed-array instance nor a
    // native constructor currently accepts an own property through the member paths, so there is
    // nowhere to attach one - see the MemberEvaluator gap noted in the phase report.
    @Test
    public void test_map_uses_species_constructor() {
        final var base = "const ta = new Int8Array([1, 2]); ";
        assertEquals("Int8Array", str(base + "ta.map(function (x) { return x; }).constructor.name"));
        assertEquals("Int8Array", str(base + "ta.filter(function () { return true; }).constructor.name"));
        assertEquals("Int8Array", str(base + "ta.slice(0).constructor.name"));
        assertEquals("Int8Array", str(base + "ta.subarray(0).constructor.name"));
        assertEquals("true", str(base + "String(ta.subarray(0).buffer === ta.buffer)"));
        assertEquals("false", str(base + "String(ta.slice(0).buffer === ta.buffer)"));
        assertEquals("2", str(base + "String(ta.slice(0).length)"));
        assertEquals("1", str(base + "String(ta.subarray(1).length)"));
    }

    // BYTES_PER_ELEMENT is fully immutable, on both the constructor and its prototype
    @Test
    public void test_bytes_per_element_is_immutable() {
        assertEquals("4", str("String(Int32Array.BYTES_PER_ELEMENT)"));
        assertEquals("4", str("String(Int32Array.prototype.BYTES_PER_ELEMENT)"));
        assertEquals("false",
                str("String(Object.getOwnPropertyDescriptor(" + "Int32Array, 'BYTES_PER_ELEMENT').writable)"));
        assertEquals("false", str("String(Object.getOwnPropertyDescriptor("
                + "Int32Array.prototype, 'BYTES_PER_ELEMENT').configurable)"));
    }

    // Number() is ToNumeric, so a BigInt converts rather than throwing
    @Test
    public void test_number_of_bigint_converts() {
        assertEquals("7", str("String(Number(7n))"));
        assertEquals("0,2",
                str("const ta = new BigInt64Array([0n, 2n]);" + "[Number(ta[0]), Number(ta[1])].join(',')"));
    }

    // the @@toStringTag getter names the kind, and answers undefined for a foreign receiver
    @Test
    public void test_to_string_tag_reports_the_kind() {
        assertEquals("Int8Array", str("new Int8Array(1)[Symbol.toStringTag]"));
        assertEquals("[object Int8Array]", str("Object.prototype.toString.call(new Int8Array(1))"));
        assertEquals("undefined",
                str("String(Object.getOwnPropertyDescriptor(Object.getPrototypeOf(Int8Array.prototype),"
                        + " Symbol.toStringTag).get.call({}))"));
    }

    // set() reads an array-like source through [[Get]] and refuses to mix content types
    @Test
    public void test_set_reads_array_like_and_checks_content_type() {
        assertEquals("1,2,0", str("const ta = new Int8Array(3); ta.set({ length: 2, 0: 1, 1: 2 }); ta.join(',')"));
        assertEquals("0,1,2", str("const ta = new Int8Array(3); ta.set([1, 2], 1); ta.join(',')"));
        assertEquals("RangeError", caught("new Int8Array(2).set([1, 2, 3])"));
        assertEquals("RangeError", caught("new Int8Array(2).set([1], -1)"));
        assertEquals("TypeError", caught("new Int8Array(2).set(new BigInt64Array(1))"));
    }

    // a non-index write lands as an ordinary own property, while a canonical numeric index that is
    // not a valid index is discarded rather than stored
    @Test
    public void test_non_index_writes_land_as_own_properties() {
        assertEquals("7", str("const ta = new Int8Array(1); ta.tag = 7; String(ta.tag)"));
        assertEquals("undefined", str("const ta = new Int8Array(1); ta[-1] = 7; String(ta[-1])"));
        assertEquals("undefined", str("const ta = new Int8Array(1); ta[1.5] = 7; String(ta[1.5])"));
        assertEquals("5", str("const ta = new Int8Array(1);"
                + "Object.defineProperty(ta, 'v', { get() { return 5; } }); String(ta.v)"));
    }
}
