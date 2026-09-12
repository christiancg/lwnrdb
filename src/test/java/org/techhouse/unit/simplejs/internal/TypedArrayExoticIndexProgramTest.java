package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class TypedArrayExoticIndexProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_construct_over_a_buffer_without_length() {
        assertEquals(2, num("new Int8Array(new ArrayBuffer(4), 2).length"));
    }

    @Test
    public void test_construct_rejects_an_offset_past_the_buffer() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(new ArrayBuffer(4), 8)"));
    }

    @Test
    public void test_construct_without_arguments() {
        assertEquals(0, num("new Int8Array().length"));
    }

    // Regression: the wrapper's own [[Set]] used to pass the wrong receiver identity into the primitive's
    // exotic [[Set]], dropping the write onto the wrapper as a plain uncoerced property.
    @Test
    public void test_typed_array_subclass_index_write_goes_through_to_the_buffer() {
        final var source = """
                class ExtendedUint8Array extends Uint8Array {
                    constructor() {
                        super(10);
                        this[0] = 255;
                        this[1] = 0xFFA;
                    }
                }
                const eua = new ExtendedUint8Array();
                [eua.length, eua[0], eua[1]]
                """;
        final var result = (org.techhouse.simplejs.values.JsArray) Interpreter.run(source);
        assertEquals(10, ((JsNumber) result.get(0)).getValue());
        assertEquals(255, ((JsNumber) result.get(1)).getValue());
        assertEquals(250, ((JsNumber) result.get(2)).getValue());
    }

    @Test
    public void test_data_view_rejects_a_detached_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4);
                buffer.transfer();
                new DataView(buffer)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_data_view_rejects_an_offset_past_the_buffer() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DataView(new ArrayBuffer(4), 8)"));
    }

    @Test
    public void test_data_view_rejects_an_oversized_length() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new DataView(new ArrayBuffer(4), 0, 8)"));
    }

    @Test
    public void test_data_view_byte_length() {
        assertEquals(2, num("new DataView(new ArrayBuffer(4), 1, 2).byteLength"));
    }

    @Test
    public void test_data_view_float16_round_trip() {
        final var source = """
                const view = new DataView(new ArrayBuffer(2));
                view.setFloat16(0, 1.5);
                view.getFloat16(0)
                """;
        assertEquals(1.5, num(source));
    }

    @Test
    public void test_set_from_a_typed_array() {
        assertEquals("0,1,2,0", str("const t = new Int8Array(4); t.set(new Int8Array([1, 2]), 1); t.join(',')"));
    }

    @Test
    public void test_set_from_an_array_like() {
        assertEquals("0,7,8", str("const t = new Int8Array(3); t.set([7, 8], 1); t.join(',')"));
    }

    @Test
    public void test_set_rejects_an_oversized_source() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(2).set([1, 2], 2)"));
    }

    @Test
    public void test_set_rejects_a_negative_offset() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array(2).set([1], -1)"));
    }

    @Test
    public void test_last_index_of_with_a_negative_start() {
        assertEquals(2, num("new Int8Array([1, 2, 1]).lastIndexOf(1, -1)"));
    }

    @Test
    public void test_last_index_of_on_an_empty_array() {
        assertEquals(-1, num("new Int8Array(0).lastIndexOf(1)"));
    }

    @Test
    public void test_index_of_with_a_negative_start() {
        assertEquals(2, num("new Int8Array([1, 2, 3]).indexOf(3, -1)"));
    }

    @Test
    public void test_find_without_a_match() {
        assertEquals("undefined,-1",
                str("String(new Int8Array([1]).find(x => x > 5)) + ',' + new Int8Array([1]).findIndex(x => x > 5)"));
    }

    @Test
    public void test_with_rejects_an_out_of_range_index() {
        assertThrows(RangeErrorException.class, () -> Interpreter.run("new Int8Array([1]).with(5, 1)"));
    }

    @Test
    public void test_non_canonical_index_write_is_ordinary() {
        final var source = """
                const t = new Int8Array(2);
                t['1.0'] = 5;
                String(t['1.0']) + ':' + t[1]
                """;
        assertEquals("5:0", str(source));
    }

    @Test
    public void test_out_of_range_write_is_dropped() {
        assertEquals("undefined", str("const t = new Int8Array(1); t[5] = 3; String(t[5])"));
    }

    @Test
    public void test_define_property_on_an_index() {
        final var source = """
                const t = new Int8Array(1);
                Object.defineProperty(t, '0', { value: 7 });
                t[0]
                """;
        assertEquals(7, num(source));
    }

    @Test
    public void test_detached_index_read() {
        final var source = """
                const buffer = new ArrayBuffer(2);
                const t = new Int8Array(buffer);
                buffer.transfer();
                String(t[0])
                """;
        assertEquals("undefined", str(source));
    }

    @Test
    public void test_view_out_of_bounds_after_shrink() {
        final var source = """
                const buffer = new ArrayBuffer(8, { maxByteLength: 8 });
                const t = new Int8Array(buffer, 4, 2);
                buffer.resize(2);
                t.length
                """;
        assertEquals(0, num(source));
    }

    @Test
    public void test_offset_past_a_resizable_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4, { maxByteLength: 8 });
                new Int8Array(buffer, 8)
                """;
        assertThrows(RangeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_data_view_offset_past_a_resizable_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4, { maxByteLength: 8 });
                new DataView(buffer, 8)
                """;
        assertThrows(RangeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_data_view_over_a_detached_resizable_buffer() {
        final var source = """
                const buffer = new ArrayBuffer(4, { maxByteLength: 8 });
                buffer.transfer();
                new DataView(buffer, 0)
                """;
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    @Test
    public void test_search_methods_without_an_argument() {
        assertEquals("false:-1", str("String(new Int8Array([1]).includes()) + ':' + new Int8Array([1]).indexOf()"));
    }

    @Test
    public void test_set_over_a_bigint_array() {
        assertEquals("1", str("const t = new BigInt64Array(1); t.set([1n]); String(t[0])"));
    }

    @Test
    public void test_byte_offset_accessor() {
        assertEquals(2, num("new Int8Array(new ArrayBuffer(4), 2, 1).byteOffset"));
    }
}
