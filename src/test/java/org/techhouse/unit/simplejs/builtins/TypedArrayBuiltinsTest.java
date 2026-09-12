package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.JsEval;

public class TypedArrayBuiltinsTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String caught(String expression) {
        return str("let caught = 'none'; try { " + expression + "; } catch (e) { caught = e.name; } caught");
    }

    @Test
    public void test_array_buffer_prototype_getter_wins_over_allocation_range_error() {
        assertEquals("DummyError", str("""
                function DummyError() {}
                var newTarget = Object.defineProperty(function(){}.bind(null), 'prototype', {
                    get: function() { throw new DummyError(); }
                });
                let caught = 'none';
                try { Reflect.construct(ArrayBuffer, [7 * 1125899906842624], newTarget); }
                catch (e) { caught = e.constructor.name; }
                caught
                """));
    }

    @Test
    public void test_array_buffer_max_byte_length_check_precedes_prototype_observation() {
        assertEquals("RangeError,0", str("""
                let getterCalls = 0;
                let newTarget = Object.defineProperty(function(){}.bind(null), 'prototype', {
                    get: function() { getterCalls += 1; throw new Test262LikeError(); }
                });
                function Test262LikeError() {}
                let caught = 'none';
                try { Reflect.construct(ArrayBuffer, [10, { maxByteLength: 0 }], newTarget); }
                catch (e) { caught = e instanceof RangeError ? 'RangeError' : 'other'; }
                [caught, getterCalls].join(',')
                """));
    }

    @Test
    public void test_ordinary_lengths_still_allocate() {
        assertEquals("8", str("String(new ArrayBuffer(8).byteLength)"));
        assertEquals("0", str("String(new ArrayBuffer().byteLength)"));
        assertEquals("0", str("String(new ArrayBuffer(undefined).byteLength)"));
        assertEquals("4", str("String(new Int32Array(1).byteLength)"));
    }

    private static final String DETACHED = "const b = new ArrayBuffer(8); const ta = new Int8Array(b); b.transfer(0); ";

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
        assertEquals("TypeError", caught(DETACHED + "ta.subarray(0)"));
    }

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

    @Test
    public void test_length_tracking_view_follows_resize() {
        final var base = "const b = new ArrayBuffer(4, { maxByteLength: 8 }); const ta = new Int8Array(b); ";
        assertEquals("4", str(base + "String(ta.length)"));
        assertEquals("8", str(base + "b.resize(8); String(ta.length)"));
        assertEquals("2", str(base + "b.resize(2); String(ta.length)"));
        assertEquals("0,0", str(base + "b.resize(2); ta.join(',')"));
    }

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

    @Test
    public void test_callbacks_honour_this_arg() {
        final var host = "const host = { mark: 42 }; const ta = new Int8Array([1, 2]); ";
        assertEquals("42,42", str(
                host + "const seen = [];" + "ta.forEach(function () { seen.push(this.mark); }, host); seen.join(',')"));
        assertEquals("42", str(host + "String(ta.map(function () { return this.mark; }, host)[0])"));
        assertEquals("2", str(host + "String(ta.filter(function () { return this.mark === 42; }, host).length)"));
        assertEquals("1", str(host + "String(ta.find(function () { return this.mark === 42; }, host))"));
    }

    // A non-default species is not exercised here because neither a typed-array instance nor a native
    // constructor accepts an own property through the member paths, so there is nowhere to attach one.
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

    @Test
    public void test_bytes_per_element_is_immutable() {
        assertEquals("4", str("String(Int32Array.BYTES_PER_ELEMENT)"));
        assertEquals("4", str("String(Int32Array.prototype.BYTES_PER_ELEMENT)"));
        assertEquals("false",
                str("String(Object.getOwnPropertyDescriptor(" + "Int32Array, 'BYTES_PER_ELEMENT').writable)"));
        assertEquals("false", str("String(Object.getOwnPropertyDescriptor("
                + "Int32Array.prototype, 'BYTES_PER_ELEMENT').configurable)"));
    }

    @Test
    public void test_number_of_bigint_converts() {
        assertEquals("7", str("String(Number(7n))"));
        assertEquals("0,2",
                str("const ta = new BigInt64Array([0n, 2n]);" + "[Number(ta[0]), Number(ta[1])].join(',')"));
    }

    @Test
    public void test_to_string_tag_reports_the_kind() {
        assertEquals("Int8Array", str("new Int8Array(1)[Symbol.toStringTag]"));
        assertEquals("[object Int8Array]", str("Object.prototype.toString.call(new Int8Array(1))"));
        assertEquals("undefined",
                str("String(Object.getOwnPropertyDescriptor(Object.getPrototypeOf(Int8Array.prototype),"
                        + " Symbol.toStringTag).get.call({}))"));
    }

    @Test
    public void test_own_keys_order_indices_then_strings() {
        assertEquals("0,1,tag",
                str("const ta = new Int8Array(2); ta.tag = 1;" + "Object.getOwnPropertyNames(ta).join(',')"));
        assertEquals("", str("const ta = new Int8Array(4).subarray(4); Object.getOwnPropertyNames(ta).join(',')"));
        assertEquals("0,1", str("const b = new ArrayBuffer(4, { maxByteLength: 4 });"
                + "const ta = new Int8Array(b); b.resize(2); Object.getOwnPropertyNames(ta).join(',')"));
    }

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

    @Test
    public void test_constructors_require_new() {
        assertEquals("TypeError", caught("Int8Array(1)"));
        assertEquals("TypeError", caught("ArrayBuffer(8)"));
        assertEquals("TypeError", caught("DataView(new ArrayBuffer(8))"));
        assertEquals("3", str("String(Int8Array.length)"));
    }

    @Test
    public void test_is_view_recognises_both_view_kinds() {
        assertEquals("true", str("String(ArrayBuffer.isView(new Int8Array(1)))"));
        assertEquals("true", str("String(ArrayBuffer.isView(new DataView(new ArrayBuffer(1))))"));
        assertEquals("false", str("String(ArrayBuffer.isView({}))"));
        assertEquals("false", str("String(ArrayBuffer.isView())"));
        assertEquals("true", str("const isView = ArrayBuffer.isView; String(isView(new Int8Array(1)))"));
    }

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

    @Test
    public void test_has_property_walks_a_foreign_prototype() {
        assertTrue(JsEval.bool("""
                let trapped = false;
                const handler = { has() { trapped = true; return true; } };
                const proxy = new Proxy({}, handler);
                const sample = new Int32Array(1);
                Object.setPrototypeOf(sample, proxy);
                Reflect.has(sample, "foo") && trapped
                """));
    }
}
