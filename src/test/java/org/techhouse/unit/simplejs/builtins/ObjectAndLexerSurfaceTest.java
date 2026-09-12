package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

public class ObjectAndLexerSurfaceTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String attempt() {
        return str("(() => { try { return String(" + """
                (() => { const arr = [1, 2]; Object.freeze(arr); arr[0] = 9; return arr[0]; })()
                """ + "); } catch (e) { return e.constructor.name; } })()");
    }

    @Test
    public void test_a_template_decodes_every_escape_form() {
        assertEquals("\"a\\nb\\tc\\rd\\be\\f\\u000bg\\u0000hAiBjCk`l\\\\m$n1o\"", str("""
                JSON.stringify(`a\\nb\\tc\\rd\\be\\f\\vg\\0hA\\x69Bj\\u{43}k\\`l\\\\m\\$n${1}o`)
                """));
    }

    @Test
    public void test_a_template_line_continuation_joins_the_lines() {
        assertEquals("linejoined", str("`line\\\njoined`"));
    }

    @Test
    public void test_the_raw_text_keeps_the_escapes() {
        assertEquals("a\\nb:a\nb", str("String.raw`a\\nb` + ':' + `a\\nb`"));
    }

    @Test
    public void test_a_typed_array_reports_its_brand_through_object_to_string() {
        assertEquals("[object Uint8Array]", str("Object.prototype.toString.call(new Uint8Array(1))"));
    }

    @Test
    public void test_the_typed_array_geometry_accessors_read_their_receiver() {
        assertEquals("buffer,4,2,4", str("""
                const view = new Uint8Array(new ArrayBuffer(8), 2, 4);
                const proto = Object.getPrototypeOf(Uint8Array.prototype);
                ['buffer', 'byteLength', 'byteOffset', 'length'].map(key => {
                    const read = Object.getOwnPropertyDescriptor(proto, key).get.call(view);
                    return read instanceof ArrayBuffer ? 'buffer' : read;
                }).join(',')
                """));
    }

    @Test
    public void test_group_by_buckets_by_string_and_numeric_keys() {
        assertEquals("{\"odd\":[1,3],\"even\":[2,4]}",
                str("JSON.stringify(Object.groupBy([1, 2, 3, 4], n => (n % 2 ? 'odd' : 'even')))"));
        assertEquals("{\"1\":[1],\"2\":[2]}", str("JSON.stringify(Object.groupBy([1, 2], n => n))"));
    }

    @Test
    public void test_map_group_by_keeps_the_callback_key() {
        assertEquals("1,0", str("[...Map.groupBy([1, 2, 3], n => n % 2).keys()].join(',')"));
    }

    @Test
    public void test_an_own_symbol_key_is_reported_and_described() {
        assertEquals("true:false:{\"value\":1,\"writable\":true,\"enumerable\":true,\"configurable\":true}", str("""
                const S = Symbol('s');
                const holder = { [S]: 1 };
                [
                    Object.hasOwn(holder, S),
                    Object.hasOwn({}, S),
                    JSON.stringify(Object.getOwnPropertyDescriptor(holder, S))
                ].join(':')
                """));
    }

    @Test
    public void test_sealing_and_freezing_reach_an_array() {
        assertEquals("true:false:false", str("""
                const arr = [1, 2];
                Object.seal(arr);
                [Object.isSealed(arr), Object.isFrozen(arr), Object.isExtensible(arr)].join(':')
                """));
    }

    @Test
    public void test_a_frozen_array_refuses_an_index_write() {
        assertEquals("TypeError", attempt());
    }
}
