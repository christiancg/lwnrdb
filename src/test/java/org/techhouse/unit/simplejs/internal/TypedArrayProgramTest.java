package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TypedArrayProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_spread() {
        assertEquals("1,2,3", str("[...new Uint8Array([1, 2, 3])].join(',')"));
    }

    @Test
    public void test_map_and_reduce() {
        assertEquals("2,4,6", str("new Uint8Array([1, 2, 3]).map(x => x * 2).join(',')"));
        assertEquals(10, num("new Int32Array([1, 2, 3, 4]).reduce((a, b) => a + b, 0)"));
    }

    @Test
    public void test_filter() {
        assertEquals(2, num("new Int8Array([1, 2, 3, 4]).filter(x => x % 2 === 0).length"));
    }

    @Test
    public void test_search_and_at() {
        assertEquals(1, num("new Int16Array([10, 20, 30]).indexOf(20)"));
        assertTrue(bool("new Int16Array([10, 20, 30]).includes(30)"));
        assertEquals(30, num("new Int16Array([10, 20, 30]).at(-1)"));
    }

    @Test
    public void test_geometry_and_statics() {
        assertEquals(16, num("new Float64Array(2).byteLength"));
        assertEquals(4, num("Int32Array.BYTES_PER_ELEMENT"));
        assertTrue(bool("ArrayBuffer.isView(new Int8Array(1))"));
        assertEquals(2, num("Array.from(new Uint8Array([1, 2])).length"));
    }

    // test262's testTypedArray.js harness relies on `Object.getPrototypeOf(Int8Array)` being the shared
    // %TypedArray% intrinsic.
    @Test
    public void test_shared_typed_array_intrinsic() {
        final var source = """
                let TypedArray = Object.getPrototypeOf(Int8Array);
                let sameForEveryKind = TypedArray === Object.getPrototypeOf(Uint8Array)
                    && TypedArray === Object.getPrototypeOf(Float64Array);
                let protoLinked = Object.getPrototypeOf(Int8Array.prototype) === TypedArray.prototype;
                let iteratorIsValues = TypedArray.prototype[Symbol.iterator] === TypedArray.prototype.values;
                let notDirectlyConstructable;
                try { new TypedArray(); notDirectlyConstructable = false; }
                catch (e) { notDirectlyConstructable = e instanceof TypeError; }
                JSON.stringify([sameForEveryKind, protoLinked, iteratorIsValues, notDirectlyConstructable])
                """;
        assertEquals("[true,true,true,true]", str(source));
    }

    @Test
    public void test_typed_array_geometry_accessor_rejects_wrong_receiver() {
        final var source = """
                let TypedArrayPrototype = Object.getPrototypeOf(Int8Array).prototype;
                let names = ['length', 'byteLength', 'byteOffset', 'buffer'];
                let results = names.map(name => {
                    try { TypedArrayPrototype[name]; return 'no-throw'; }
                    catch (e) { return e instanceof TypeError; }
                });
                JSON.stringify(results)
                """;
        assertEquals("[true,true,true,true]", str(source));
    }

    @Test
    public void test_typed_array_non_integer_numeric_key_bypasses_prototype() {
        final var source = """
                let TypedArrayPrototype = Object.getPrototypeOf(Int8Array).prototype;
                Object.defineProperty(TypedArrayPrototype, '1.5', { get() { throw new Error('should not run'); } });
                let a = new Int8Array([1, 2, 3]);
                typeof a['1.5']
                """;
        assertEquals("undefined", str(source));
    }

    @Test
    public void test_for_each() {
        assertEquals("0:10,1:20",
                str("let out = []; new Int8Array([10, 20]).forEach((v, i) => out.push(i + ':' + v)); out.join(',')"));
    }

    @Test
    public void test_reduce_variants() {
        assertEquals("4321", str("new Int8Array([1, 2, 3, 4]).reduceRight((a, b) => a + '' + b, '')"));
        assertEquals(10, num("new Int8Array([1, 2, 3, 4]).reduce((a, b) => a + b)"));
    }

    @Test
    public void test_find() {
        assertEquals(20, num("new Int16Array([10, 20, 30]).find(x => x > 15)"));
        assertEquals(2, num("new Int16Array([10, 20, 30]).findIndex(x => x > 25)"));
        assertEquals(-1, num("new Int16Array([10, 20, 30]).findIndex(x => x > 99)"));
    }

    @Test
    public void test_some_every() {
        assertTrue(bool("new Int8Array([1, 2, 3]).some(x => x === 2)"));
        assertTrue(bool("new Int8Array([2, 4, 6]).every(x => x % 2 === 0)"));
        assertFalse(bool("new Int8Array([2, 3, 6]).every(x => x % 2 === 0)"));
    }

    @Test
    public void test_last_index_and_missing() {
        assertEquals(3, num("new Int8Array([1, 2, 1, 2]).lastIndexOf(2)"));
        assertFalse(bool("new Int8Array([1, 2, 3]).includes(9)"));
        assertEquals(-1, num("new Int8Array([1, 2, 3]).indexOf(9)"));
    }

    @Test
    public void test_join_and_to_string() {
        assertEquals("1-2-3", str("new Int8Array([1, 2, 3]).join('-')"));
        assertEquals("1,2,3", str("new Int8Array([1, 2, 3]).toString()"));
    }

    @Test
    public void test_reverse() {
        assertEquals("3,2,1", str("new Int8Array([1, 2, 3]).reverse().join(',')"));
    }

    @Test
    public void test_iterators() {
        assertEquals("0,1,2", str("[...new Int8Array([9, 8, 7]).keys()].join(',')"));
        assertEquals("9,8,7", str("[...new Int8Array([9, 8, 7]).values()].join(',')"));
        assertEquals(12, num("let n = 0; for (const [i, v] of new Int8Array([5, 6]).entries()) n += i + v; n"));
    }

    @Test
    public void test_missing_callback_throws() {
        assertEquals("TypeError", str("let n; try { new Int8Array([1]).map(); } catch (e) { n = e.name } n"));
    }

    @Test
    public void test_non_callable_callback_throws_even_on_empty_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array(0).find(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("new Int8Array([1, 2]).find(null)"));
    }

    @Test
    public void test_json_stringify() {
        assertEquals("[1,2,3]", str("JSON.stringify(new Uint8Array([1, 2, 3]))"));
        assertEquals("{}", str("JSON.stringify(new ArrayBuffer(4))"));
        assertEquals("{}", str("JSON.stringify(new DataView(new ArrayBuffer(4)))"));
    }

    @Test
    public void test_negative_indices() {
        assertEquals("3,4", str("new Uint8Array([1, 2, 3, 4]).slice(-2).join(',')"));
        assertEquals("2,3", str("new Uint8Array([1, 2, 3, 4]).subarray(-3, -1).join(',')"));
    }

    @Test
    public void test_reduce_empty_throws() {
        assertEquals("TypeError",
                str("let n; try { new Int8Array(0).reduce((a, b) => a + b); } catch (e) { n = e.name } n"));
    }

    @Test
    public void test_unknown_members() {
        assertTrue(bool("new Int8Array(1).nope === undefined"));
        assertTrue(bool("new DataView(new ArrayBuffer(4)).nope === undefined"));
        assertTrue(bool("new ArrayBuffer(4).nope === undefined"));
    }

    @Test
    public void test_math_f16round() {
        assertEquals(num("new Float16Array([1.337])[0]"), num("Math.f16round(1.337)"));
    }

    @Test
    public void test_auto_length_view_grows() {
        final var source = """
                const buf = new ArrayBuffer(8, { maxByteLength: 16 });
                const view = new Int32Array(buf);
                const before = view.length;
                buf.resize(16);
                before * 100 + view.length
                """;
        assertEquals(2 * 100 + 4, num(source));
    }

    @Test
    public void test_auto_length_view_shrinks() {
        final var source = """
                const buf = new ArrayBuffer(16, { maxByteLength: 16 });
                const view = new Int32Array(buf);
                const before = view.length;
                buf.resize(4);
                before * 100 + view.length
                """;
        assertEquals(4 * 100 + 1, num(source));
    }

    @Test
    public void test_explicit_length_view_fixed() {
        final var stillFits = """
                const buf = new ArrayBuffer(16, { maxByteLength: 16 });
                const view = new Int32Array(buf, 0, 2);
                buf.resize(12);
                view.length
                """;
        assertEquals(2, num(stillFits));
        final var outOfBounds = """
                const buf = new ArrayBuffer(16, { maxByteLength: 16 });
                const view = new Int32Array(buf, 0, 2);
                buf.resize(4);
                view.length
                """;
        assertEquals(0, num(outOfBounds));
    }

    @Test
    public void test_non_resizable_view_fixed() {
        final var source = """
                const buf = new ArrayBuffer(16);
                const view = new Int32Array(buf);
                view.length
                """;
        assertEquals(4, num(source));
    }

    @Test
    public void test_auto_length_view_byte_length_tracks() {
        final var source = """
                const buf = new ArrayBuffer(8, { maxByteLength: 16 });
                const view = new Float64Array(buf);
                buf.resize(16);
                view.byteLength
                """;
        assertEquals(16, num(source));
    }

    @Test
    public void test_sort_is_numeric() {
        assertEquals("2,10,33", str("new Int32Array([10, 2, 33]).sort().join(',')"));
        assertEquals("33,10,2", str("new Int32Array([10, 2, 33]).sort((a, b) => b - a).join(',')"));
        assertEquals("1,2", str("new BigInt64Array([2n, 1n]).sort().join(',')"));
    }

    @Test
    public void test_with_range_error() {
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("new Int8Array(2).with(5, 1)"));
        assertThrows(org.techhouse.simplejs.exceptions.RangeErrorException.class,
                () -> Interpreter.run("new Int8Array(2).with(-5, 1)"));
        assertEquals("0,9", str("new Int8Array(2).with(-1, 9).join(',')"));
    }

    @Test
    public void test_typed_array_includes_same_value_zero() {
        assertTrue(bool("new Float64Array([NaN]).includes(NaN)"));
        assertTrue(bool("new Float32Array([NaN]).includes(NaN)"));
        assertTrue(bool("new Float64Array([-0]).includes(0)"));
        assertFalse(bool("new Float64Array([NaN]).indexOf(NaN) >= 0"));
    }

    @Test
    public void test_own_length_property_shadows_computed_length() {
        assertEquals(4000, num("""
                const ta = new Uint8Array(1);
                Object.defineProperty(ta, 'length', { value: 4000 });
                ta.length
                """));
    }

    @Test
    public void test_length_without_own_override_is_computed() {
        assertEquals(3, num("new Uint8Array(3).length"));
    }
}
