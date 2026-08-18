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

public class TypedArrayBuiltinsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
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
        // subarray skips ValidateTypedArray, but the view it species-creates over the same detached
        // buffer cannot be built, so the rejection arrives from the constructor instead
        assertEquals("TypeError", caught(DETACHED + "ta.subarray(0)"));
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

    // every canonical index inside the view is an own data property, and the spec made them
    // writable, enumerable and configurable so a shrinking buffer can drop them
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

    // ownPropertyKeys lists the indices ascending, then the table's string keys, then its symbols
    @Test
    public void test_own_keys_order_indices_then_strings() {
        assertEquals("0,1,tag",
                str("const ta = new Int8Array(2); ta.tag = 1;" + "Object.getOwnPropertyNames(ta).join(',')"));
        assertEquals("", str("const ta = new Int8Array(4).subarray(4); Object.getOwnPropertyNames(ta).join(',')"));
        assertEquals("0,1", str("const b = new ArrayBuffer(4, { maxByteLength: 4 });"
                + "const ta = new Int8Array(b); b.resize(2); Object.getOwnPropertyNames(ta).join(',')"));
    }

    // a numeric string Number::toString would never produce is an ordinary key, not an index
    @Test
    public void test_non_canonical_numeric_keys_are_ordinary() {
        final var base = "const ta = new Int8Array(2); ";
        for (final var key : new String[]{"-0", "1.0", "+1", " 1", "01"}) {
            assertEquals("false", str(base + "String(Object.prototype.hasOwnProperty.call(ta, '" + key + "'))"));
        }
        assertEquals("42",
                str(base + "Object.defineProperty(ta, '1.0', { value: 42, writable: true, configurable: true });"
                        + "String(ta['1.0'])"));
        assertEquals("false", str(base + "String(Reflect.defineProperty(ta, '-0', { value: 1 }))"));
    }

    // [[DefineOwnProperty]] on an index writes the element; anything the exotic cannot honour - an
    // accessor, a cleared attribute, an out-of-range index - is rejected
    @Test
    public void test_define_own_property_on_indices() {
        final var base = "const ta = new Int8Array(2); ";
        assertEquals("7", str(base + "Object.defineProperty(ta, '0', { value: 7 }); String(ta[0])"));
        assertEquals("true", str(base + "String(Reflect.defineProperty(ta, '0', { value: 7 }))"));
        assertEquals("false", str(base + "String(Reflect.defineProperty(ta, '2', { value: 7 }))"));
        assertEquals("false", str(base + "String(Reflect.defineProperty(ta, '0', { get() { return 1; } }))"));
        assertEquals("false", str(base + "String(Reflect.defineProperty(ta, '0', { value: 7, writable: false }))"));
        assertEquals("false", str(base + "String(Reflect.defineProperty(ta, '0', { value: 7, enumerable: false }))"));
        assertEquals("false", str(base + "String(Reflect.defineProperty(ta, '0', { value: 7, configurable: false }))"));
        assertEquals("TypeError", caught(base + "Object.defineProperty(ta, '2', { value: 7 })"));
    }

    // [[Delete]] of a live index fails, while anything absent - out of range, non-canonical, gone
    // with a detached buffer - deletes vacuously
    @Test
    public void test_delete_of_a_valid_index_is_refused() {
        final var base = "const ta = new Int8Array(2); ";
        assertEquals("false", str(base + "String(Reflect.deleteProperty(ta, '0'))"));
        assertEquals("true", str(base + "String(Reflect.deleteProperty(ta, '2'))"));
        assertEquals("true", str(base + "String(Reflect.deleteProperty(ta, '1.5'))"));
        assertEquals("true", str(base + "String(Reflect.deleteProperty(ta, 'missing'))"));
        assertEquals("true", str(DETACHED + "String(Reflect.deleteProperty(ta, '0'))"));
    }

    // an out-of-bounds element write is dropped without reporting failure
    @Test
    public void test_out_of_bounds_write_is_a_silent_no_op() {
        final var base = "const ta = new Int8Array(1); ";
        assertEquals("undefined", str(base + "ta[5] = 3; String(ta[5])"));
        assertEquals("false", str(base + "ta[5] = 3; String(Object.prototype.hasOwnProperty.call(ta, '5'))"));
        assertEquals("true", str(base + "String(Reflect.set(ta, '5', 3))"));
        assertEquals("none", caught(DETACHED + "ta[0] = 1"));
        assertEquals("undefined", str(DETACHED + "ta[0] = 1; String(ta[0])"));
    }

    // an element write runs the value's own valueOf even when the value is itself an exotic object
    @Test
    public void test_element_write_coerces_through_ordinary_to_primitive() {
        assertEquals("Test262Error", caught("const ta = new Int8Array(1); const src = new Int8Array(1);"
                + "src.valueOf = function () { throw { name: 'Test262Error' }; }; ta[0] = src"));
        assertEquals("3", str("const ta = new Int8Array(1); const src = new Int8Array(1);"
                + "src.valueOf = function () { return 3; }; ta[0] = src; String(ta[0])"));
    }

    // narrowing a double straight to a half: rounding through float first lands on the wrong value
    @Test
    public void test_float16_writes_round_once() {
        assertEquals("5.960464477539063e-8",
                str("const ta = new Float16Array(1); ta[0] = 2.980232238769532e-8; String(ta[0])"));
        assertEquals("5.960464477539063e-8", str("const v = new DataView(new ArrayBuffer(2));"
                + "v.setFloat16(0, 2.980232238769532e-8); String(v.getFloat16(0))"));
    }

    // from/of construct through the `this` constructor rather than a fixed kind
    @Test
    public void test_from_and_of_honour_the_this_constructor() {
        assertEquals("1,2", str("Int8Array.of(1, 2).join(',')"));
        assertEquals("2,4", str("Int8Array.from([1, 2], function (x) { return x * 2; }).join(',')"));
        assertEquals("Int32Array", str("const TypedArray = Object.getPrototypeOf(Int8Array);"
                + "TypedArray.of.call(Int32Array, 1).constructor.name"));
        assertEquals("TypeError", caught("Object.getPrototypeOf(Int8Array).of.call(null, 1)"));
        assertEquals("TypeError", caught("Int8Array.from([1], 'not a function')"));
        assertEquals("42", str("const host = { mark: 42 };"
                + "String(Int8Array.from([1], function () { return this.mark; }, host)[0])"));
    }

    // a constructor-only builtin rejects a plain call and reads new.target's prototype
    @Test
    public void test_constructors_require_new() {
        assertEquals("TypeError", caught("Int8Array(1)"));
        assertEquals("TypeError", caught("ArrayBuffer(8)"));
        assertEquals("TypeError", caught("DataView(new ArrayBuffer(8))"));
        assertEquals("3", str("String(Int8Array.length)"));
    }

    // DataView has only one required parameter (buffer); byteOffset/byteLength are optional and
    // don't count toward the builtin's length.
    @Test
    public void test_data_view_length_is_one() {
        assertEquals("1", str("String(DataView.length)"));
    }

    // OrdinaryCreateFromConstructor: Reflect.construct(DataView, args, newTarget) links the new
    // instance's prototype to newTarget.prototype instead of %DataView.prototype%, wrapping the view
    // in a plain object the way the other builtins with internal state already do.
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

    // A plain `new DataView(...)` still links to the ordinary %DataView.prototype%.
    @Test
    public void test_data_view_default_prototype_unaffected() {
        assertTrue(bool("Object.getPrototypeOf(new DataView(new ArrayBuffer(8))) === DataView.prototype"));
    }

    // isView answers for both view kinds and for nothing else
    @Test
    public void test_is_view_recognises_both_view_kinds() {
        assertEquals("true", str("String(ArrayBuffer.isView(new Int8Array(1)))"));
        assertEquals("true", str("String(ArrayBuffer.isView(new DataView(new ArrayBuffer(1))))"));
        assertEquals("false", str("String(ArrayBuffer.isView({}))"));
        assertEquals("false", str("String(ArrayBuffer.isView())"));
        assertEquals("true", str("const isView = ArrayBuffer.isView; String(isView(new Int8Array(1)))"));
    }

    // indexOf/lastIndexOf compare strictly, and an explicitly passed undefined fromIndex is still
    // a supplied argument that ToIntegerOrInfinity turns into 0
    @Test
    public void test_index_searches_are_strict() {
        final var base = "const ta = new Int8Array([1, 2, 3]); ";
        assertEquals("-1", str(base + "String(ta.indexOf('2'))"));
        assertEquals("-1", str(base + "String(ta.lastIndexOf('2'))"));
        assertEquals("1", str(base + "String(ta.indexOf(2))"));
        assertEquals("-1", str(base + "String(ta.lastIndexOf(3, undefined))"));
        assertEquals("2", str(base + "String(ta.lastIndexOf(3))"));
    }

    // FromBase64: whitespace is skipped, and the last chunk is governed by lastChunkHandling
    @Test
    public void test_from_base64_decodes_per_last_chunk_handling() {
        assertEquals("101,120,97,102", str("Uint8Array.fromBase64('ZXhhZg==').join(',')"));
        assertEquals("102", str("Uint8Array.fromBase64('Z\\tg==').join(',')"));
        assertEquals("101,120,97",
                str("Uint8Array.fromBase64('ZXhhZg', { lastChunkHandling: 'stop-before-partial' }).join(',')"));
        assertEquals("SyntaxError", caught("Uint8Array.fromBase64('ZXhhZg', { lastChunkHandling: 'strict' })"));
        assertEquals("SyntaxError", caught("Uint8Array.fromBase64('ZXhhZh==', { lastChunkHandling: 'strict' })"));
        assertEquals("SyntaxError", caught("Uint8Array.fromBase64('ZXhhZg=')"));
        assertEquals("SyntaxError", caught("Uint8Array.fromBase64('ZXhhZg===')"));
        assertEquals("SyntaxError", caught("Uint8Array.fromBase64('A')"));
        assertEquals("", str("Uint8Array.fromBase64('A', { lastChunkHandling: 'stop-before-partial' }).join(',')"));
        assertEquals("199,239,242", str("Uint8Array.fromBase64('x-_y', { alphabet: 'base64url' }).join(',')"));
        assertEquals("TypeError", caught("Uint8Array.fromBase64('Zg==', { alphabet: 'base32' })"));
        assertEquals("TypeError", caught("Uint8Array.fromBase64('Zg==', { lastChunkHandling: 'nope' })"));
        assertEquals("TypeError", caught("Uint8Array.fromBase64(1)"));
        assertEquals("1", str("String(Uint8Array.fromBase64.length)"));
    }

    // FromHex rejects an odd length before decoding anything, and setFromHex writes the valid prefix
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

    // setFromBase64 decodes only as much as the target holds and reports what it consumed
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

    // toBase64/toHex read their options once and reject a detached receiver
    @Test
    public void test_to_base64_and_to_hex() {
        assertEquals("Zm9v", str("new Uint8Array([102, 111, 111]).toBase64()"));
        assertEquals("x-_y", str("new Uint8Array([199, 239, 242]).toBase64({ alphabet: 'base64url' })"));
        assertEquals("/w", str("new Uint8Array([255]).toBase64({ omitPadding: true })"));
        assertEquals("666f6f", str("new Uint8Array([102, 111, 111]).toHex()"));
        assertEquals("TypeError", caught("new Uint8Array(1).toBase64({ alphabet: 'base32' })"));
        assertEquals("TypeError",
                caught("const b = new ArrayBuffer(2); const u = new Uint8Array(b); b.transfer(0); u.toBase64()"));
        assertEquals("TypeError",
                caught("const b = new ArrayBuffer(2); const u = new Uint8Array(b); b.transfer(0); u.toHex()"));
    }

    // `with` captures the length up front but validates the index against the live one
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

    // ArrayBuffer.prototype.slice runs SpeciesConstructor. The rejection of a non-object
    // `constructor` is not asserted here because a property write on an ArrayBuffer is still dropped
    // by the member seam, so there is no way to install one - see the report's blocked list.
    @Test
    public void test_buffer_slice_consults_the_species_constructor() {
        final var base = "const b = new ArrayBuffer(8); ";
        assertEquals("4", str(base + "String(b.slice(0, 4).byteLength)"));
        assertEquals("8", str(base + "String(b.slice(0).byteLength)"));
        assertEquals("0", str(base + "String(b.slice(4, 2).byteLength)"));
    }

    // a DataView geometry read rejects a view whose window the buffer no longer covers
    @Test
    public void test_data_view_geometry_rejects_an_out_of_bounds_view() {
        final var base = "const b = new ArrayBuffer(4, { maxByteLength: 5 }); const v = new DataView(b, 1); ";
        assertEquals("1", str(base + "String(v.byteOffset)"));
        assertEquals("1", str(base + "b.resize(1); String(v.byteOffset)"));
        assertEquals("TypeError", caught(base + "b.resize(0); v.byteOffset"));
        assertEquals("TypeError",
                caught("const b = new ArrayBuffer(4); const v = new DataView(b); b.transfer(0); v.byteOffset"));
    }

    // CanonicalNumericIndexString is the gate every exotic decision goes through
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

    // the integer-indexed [[Set]] arm: written through the array itself, answered without touching a
    // foreign receiver when the index is absent, declined only for an ordinary key
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

    // IntegerIndexedElementSet coerces the value before it decides which slot, if any, receives it:
    // an index the view cannot hold still runs the valueOf that names it.
    @Test
    public void test_index_write_coerces_before_validating_the_index() {
        final var thrower = "const t = { valueOf: function () { throw new RangeError('coerced'); } }; "
                + "const s = new Int8Array([1]); ";
        assertEquals("RangeError", caught(thrower + "s['5'] = t"));
        assertEquals("RangeError", caught(thrower + "s['1.1'] = t"));
        assertEquals("RangeError", caught(thrower + "s['-0'] = t"));
        assertEquals("RangeError", caught(thrower + "s['0'] = t"));
    }

    // ...and a CanonicalNumericIndexString naming no slot is discarded rather than stored, while an
    // ordinary key keeps running its accessor.
    @Test
    public void test_index_write_never_becomes_an_ordinary_property() {
        assertEquals("2undefined",
                str("const s = new Int8Array([1, 2]); s['1.1'] = 9; " + "String(s[1]) + String(s['1.1'])"));
        assertEquals("RangeError",
                caught("const s = new Int8Array([1]);"
                        + "Object.defineProperty(s, 'tag', { set: function () { throw new RangeError('setter'); } });"
                        + "s.tag = 1"));
    }

    // A valueOf that grows a resizable buffer makes the index it was called for valid, and the
    // write then lands.
    @Test
    public void test_index_write_sees_a_buffer_resized_by_the_coercion() {
        assertEquals("1|100",
                str("const rab = new ArrayBuffer(0, { maxByteLength: 1 });" + "const ta = new Int8Array(rab);"
                        + "ta[0] = { valueOf: function () { rab.resize(1); return 100; } };"
                        + "String(ta.length) + '|' + String(ta[0])"));
    }

    // OrdinaryCreateFromConstructor: a foreign new.target's prototype is honoured, and a non-object
    // one falls back to the kind's own intrinsic.
    @Test
    public void test_new_target_prototype_is_honoured() {
        assertEquals("true", str("function nt() {} nt.prototype = { tag: 1 };"
                + "String(Object.getPrototypeOf(Reflect.construct(Int8Array, [], nt)) === nt.prototype)"));
        assertEquals("true", str("function nt() {} nt.prototype = { tag: 1 };"
                + "String(Reflect.construct(Int8Array, [], nt).constructor === Object)"));
        assertEquals("true", str("function nt() {} nt.prototype = null;"
                + "String(Object.getPrototypeOf(Reflect.construct(Int8Array, [], nt)) === Int8Array.prototype)"));
        assertEquals("true", str("String(Object.getPrototypeOf(new Int8Array(1)) === Int8Array.prototype)"));
        assertEquals("4", str("class M extends Uint8Array {} String(new M(4).length)"));
    }

    // [[Set]] with an explicit receiver: the view answers a write addressed to itself (coercing,
    // then dropping an index it cannot hold) and declines a valid index meant for a foreign one.
    @Test
    public void test_receiver_aware_index_write() {
        assertEquals("true", str("const s = new Int8Array([1]); String(Reflect.set(s, '5', 9, s))"));
        assertEquals("RangeError", caught("const s = new Int8Array([1]);"
                + "Reflect.set(s, '5', { valueOf: function () { throw new RangeError('coerced'); } }, s)"));
        assertEquals("true", str("const s = new Int8Array([1]); String(Reflect.set(s, '5', 9, {}))"));
        assertEquals("1|9", str("const s = new Int8Array([1]); const r = {};"
                + "Reflect.set(s, '0', 9, r); String(s[0]) + '|' + String(r[0])"));
    }

    // setBigInt64 coerces the value through ToBigInt before the range is checked
    @Test
    public void test_set_big_int_coerces_the_value_first() {
        assertEquals("Test262Error", caught("const v = new DataView(new ArrayBuffer(8));"
                + "v.setBigInt64(100, { valueOf: function () { throw { name: 'Test262Error' }; } })"));
        assertEquals("7", str("const v = new DataView(new ArrayBuffer(8));"
                + "v.setBigInt64(0, { valueOf: function () { return 7n; } }); String(v.getBigInt64(0))"));
    }

    // A typed array's [[Prototype]] is now a real settable slot, so Object.setPrototypeOf actually
    // takes effect and [[HasProperty]] on a non-canonical, non-own key walks up to it (previously the
    // link was silently dropped and the lookup fell back to the intrinsic %TypedArray%.prototype).
    @Test
    public void test_set_prototype_of_is_observable_and_walks_to_it() {
        assertTrue(bool("""
                const a = new Int32Array(1);
                const b = { foo: 1 };
                Object.setPrototypeOf(a, b);
                ("foo" in a) && !("bar" in a)
                """));
    }

    // Reflect.has on a typed array with a foreign object as its prototype must consult that
    // prototype's own [[HasProperty]] (a Proxy `has` trap included) rather than silently answering
    // false because the prototype link never took effect.
    @Test
    public void test_has_property_walks_a_foreign_prototype() {
        assertTrue(bool("""
                let trapped = false;
                const handler = { has() { trapped = true; return true; } };
                const proxy = new Proxy({}, handler);
                const sample = new Int32Array(1);
                Object.setPrototypeOf(sample, proxy);
                Reflect.has(sample, "foo") && trapped
                """));
    }
}
