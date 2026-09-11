package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.test.JsEval;

public class TypedArrayDataProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    // slice copies into an independent typed array
    @Test
    public void test_slice_is_independent() {
        final var source = "const a = new Uint8Array([1, 2, 3, 4]); const s = a.slice(1, 3); s[0] = 99; a[1]";
        assertEquals(2, num(source));
    }

    // set copies a source array into the target at an offset
    @Test
    public void test_set() {
        assertEquals("0,5,6,0", str("const a = new Uint8Array(4); a.set([5, 6], 1); a.join(',')"));
    }

    // fill writes a value across a range
    @Test
    public void test_fill() {
        assertEquals(7, num("new Uint8Array(3).fill(7)[1]"));
    }

    // A DataView round-trips integers and floats with explicit endianness
    @Test
    public void test_data_view_roundtrip() {
        assertEquals(-5,
                num("const dv = new DataView(new ArrayBuffer(8)); dv.setInt32(0, -5, true); dv.getInt32(0, true)"));
        assertEquals(1.5, num("const dv = new DataView(new ArrayBuffer(8)); dv.setFloat64(0, 1.5); dv.getFloat64(0)"));
    }

    // DataView exposes every width and both endiannesses
    @Test
    public void test_data_view_widths() {
        assertEquals(127, num("const d = new DataView(new ArrayBuffer(8)); d.setInt8(0, 127); d.getInt8(0)"));
        assertEquals(255, num("const d = new DataView(new ArrayBuffer(8)); d.setUint8(0, 255); d.getUint8(0)"));
        assertEquals(-2,
                num("const d = new DataView(new ArrayBuffer(8)); d.setInt16(0, -2, true); d.getInt16(0, true)"));
        assertEquals(65535,
                num("const d = new DataView(new ArrayBuffer(8)); d.setUint16(0, 65535, true); d.getUint16(0, true)"));
        assertEquals(4294967295.0, num(
                "const d = new DataView(new ArrayBuffer(8)); d.setUint32(0, 4294967295, true); d.getUint32(0, true)"));
        assertEquals(0.5,
                num("const d = new DataView(new ArrayBuffer(8)); d.setFloat32(0, 0.5, true); d.getFloat32(0, true)"));
    }

    // DataView geometry accessors are exposed
    @Test
    public void test_data_view_geometry() {
        final var source = "const b = new ArrayBuffer(16); const d = new DataView(b, 4, 8); d.byteOffset + ',' + d.byteLength + ',' + (d.buffer === b)";
        assertEquals("4,8,true", str(source));
    }

    // set rejects a source that overflows the target
    @Test
    public void test_set_overflow_throws() {
        assertEquals("RangeError", str("let n; try { new Uint8Array(2).set([1, 2, 3]); } catch (e) { n = e.name } n"));
    }

    // set copies from another typed array; a non-array-like source is a no-op
    @Test
    public void test_set_variants() {
        assertEquals("1,2,0", str("const a = new Uint8Array(3); a.set(new Uint8Array([1, 2])); a.join(',')"));
        assertEquals("0,0", str("const a = new Uint8Array(2); a.set(5); a.join(',')"));
    }

    // fill honours explicit start/end bounds
    @Test
    public void test_fill_range() {
        assertEquals("0,7,7,0", str("new Uint8Array(4).fill(7, 1, 3).join(',')"));
    }

    // an auto-length DataView tracks the buffer and re-clamps reads past its current length
    @Test
    public void test_auto_length_data_view_reclamps() {
        final var source = """
                let result = 'no throw';
                const buf = new ArrayBuffer(8, { maxByteLength: 8 });
                const view = new DataView(buf);
                view.setInt32(4, 7);
                buf.resize(4);
                try {
                    view.getInt32(4);
                } catch (e) {
                    result = e.name;
                }
                result
                """;
        assertEquals("RangeError", str(source));
    }

    // The by-copy methods return same-kind copies
    @Test
    public void test_by_copy_methods() {
        assertEquals("2,10", str("const t = new Int32Array([10, 2]); const s = t.toSorted();"
                + " t.join(',') === '10,2' ? s.join(',') : 'mutated'"));
        assertEquals("2,10", str("new Int32Array([10, 2]).toReversed().join(',')"));
        assertEquals("9,2", str("new Int32Array([10, 2]).with(0, 9).join(',')"));
        assertTrue(JsEval.bool("new Int32Array([1]).toSorted() instanceof Int32Array"));
    }

    // findLast, findLastIndex and copyWithin
    @Test
    public void test_find_last_and_copy_within() {
        assertEquals(4, num("new Int8Array([1, 4, 2]).findLast(v => v > 2)"));
        assertEquals(1, num("new Int8Array([1, 4, 2]).findLastIndex(v => v > 2)"));
        assertEquals(-1, num("new Int8Array([1]).findLastIndex(v => v > 9)"));
        assertEquals("3,4,3,4", str("new Int8Array([1, 2, 3, 4]).copyWithin(0, 2).join(',')"));
        assertEquals("2,2,3", str("new Int8Array([1, 2, 3]).copyWithin(0, 1, 2).join(',')"));
    }
}
