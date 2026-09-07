package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsString;

/**
 * Building a view over a buffer, and the by-copy methods that have to build another one of the same kind -
 * including for a subclass, where the copy is constructed through the subclass rather than the base kind.
 */
public class TypedArrayConstructionTest {
    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String attempt() {
        return str("(() => { try { return String(" + """
                (() => { const buffer = new ArrayBuffer(4); buffer.transfer(); return new Uint8Array(buffer, 0); })()
                """ + "); } catch (e) { return e.constructor.name; } })()");
    }

    // Without an explicit length the view covers whatever the buffer has left past the offset
    @Test
    public void test_a_view_without_a_length_covers_the_rest_of_the_buffer() {
        assertEquals("4", str("new Uint8Array(new ArrayBuffer(8), 4).length + ''"));
    }

    @Test
    public void test_a_view_with_an_explicit_length_takes_only_that_many_elements() {
        assertEquals("3", str("new Uint8Array(new ArrayBuffer(8), 2, 3).length + ''"));
    }

    @Test
    public void test_a_view_over_a_resizable_buffer_tracks_its_current_length() {
        assertEquals("4:8", str("""
                const buffer = new ArrayBuffer(4, { maxByteLength: 8 });
                const view = new Uint8Array(buffer, 0);
                const before = view.length;
                buffer.resize(8);
                [before, view.length].join(':')
                """));
    }

    @Test
    public void test_a_view_over_a_detached_buffer_is_refused() {
        assertEquals("TypeError", attempt());
    }

    @Test
    public void test_a_subclass_copy_stays_a_subclass() {
        assertEquals("3:true:2:2", str("""
                class MyArr extends Uint8Array {}
                const sub = MyArr.from([1, 2, 3]);
                [sub.length, sub instanceof MyArr, sub.slice(1).length, sub.subarray(1).length].join(':')
                """));
    }

    @Test
    public void test_map_returns_a_view_of_the_same_kind() {
        assertEquals("2,4,6", str("class MyArr extends Uint8Array {} MyArr.from([1, 2, 3]).map(v => v * 2).join(',')"));
    }

    @Test
    public void test_to_locale_string_joins_the_elements() {
        assertEquals("1,2", str("new Uint8Array([1, 2]).toLocaleString()"));
    }

    @Test
    public void test_the_base64_and_hex_round_trips() {
        assertEquals("AQID:1,2,3",
                str("[new Uint8Array([1, 2, 3]).toBase64(), Uint8Array.fromBase64('AQID').join(',')]" + ".join(':')"));
        assertEquals("ff00:255,0",
                str("[new Uint8Array([255, 0]).toHex(), Uint8Array.fromHex('ff00').join(',')]" + ".join(':')"));
    }

    // setFromBase64 fills in place and reports how much of the input it consumed
    @Test
    public void test_set_from_base64_reports_what_it_read_and_wrote() {
        assertEquals("1,2,3:4:3", str("""
                const view = new Uint8Array(3);
                const result = view.setFromBase64('AQID');
                [view.join(','), result.read, result.written].join(':')
                """));
    }
}
