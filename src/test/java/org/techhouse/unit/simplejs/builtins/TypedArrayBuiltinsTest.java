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
}
