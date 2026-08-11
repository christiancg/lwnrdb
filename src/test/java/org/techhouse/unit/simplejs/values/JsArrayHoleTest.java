package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;

public class JsArrayHoleTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A hole reads as undefined but is tracked separately
    @Test
    public void test_hole_creation_and_read() {
        final var array = new JsArray();
        array.push(new JsNumber(1));
        array.pushHole();
        array.push(new JsNumber(3));
        assertEquals(3, array.length());
        assertTrue(array.isHole(1));
        assertFalse(array.isHole(0));
        assertFalse(array.isHole(5));
        assertInstanceOf(JsUndefined.class, array.get(1));
    }

    // A sparse write pads with holes rather than real undefined
    @Test
    public void test_sparse_write_pads_with_holes() {
        final var array = new JsArray();
        array.set(2, new JsNumber(9));
        assertEquals(3, array.length());
        assertTrue(array.isHole(0));
        assertTrue(array.isHole(1));
        assertFalse(array.isHole(2));
    }

    // Growing via length assignment creates holes; shrinking truncates
    @Test
    public void test_set_length() {
        final var array = new JsArray();
        array.setLength(2);
        assertEquals(2, array.length());
        assertTrue(array.isHole(0));
        array.setLength(0);
        assertEquals(0, array.length());
    }

    // removeHoles compacts and reports how many were dropped
    @Test
    public void test_remove_holes() {
        final var array = new JsArray();
        array.push(new JsNumber(1));
        array.pushHole();
        array.pushHole();
        assertEquals(2, array.removeHoles());
        assertEquals(1, array.length());
    }

    // A frozen array rejects hole and length mutation
    @Test
    public void test_frozen_array_is_unchanged() {
        final var array = new JsArray();
        array.freeze();
        array.pushHole();
        array.setLength(4);
        assertEquals(0, array.length());
    }

    // A literal hole is not visited by the callback methods
    @Test
    public void test_callbacks_skip_holes() {
        assertEquals(2, num("let n = 0; [1, , 3].forEach(() => n++); n"));
        assertEquals(2, num("[1, , 3].filter(() => true).length"));
        assertEquals(4, num("[1, , 3].reduce((a, b) => a + b, 0)"));
        assertEquals(4, num("[1, , 3].reduceRight((a, b) => a + b, 0)"));
        assertEquals(1, num("let n = 0; [1, , 3].find(v => { n++; return v === 1 }); n"));
        assertEquals(2, num("let n = 0; [1, , 3].some(() => { n++; return false }); n"));
        assertEquals(2, num("let n = 0; [1, , 3].every(() => { n++; return true }); n"));
        assertEquals(0, num("[1, , 3].findIndex(v => v === 1)"));
        assertEquals(2, num("[1, , 3].findLastIndex(v => v === 3)"));
        assertEquals(3, num("[1, , 3].findLast(v => true)"));
    }

    // map preserves holes in its output
    @Test
    public void test_map_preserves_holes() {
        assertEquals(3, num("[1, , 3].map(x => x * 2).length"));
        assertEquals(2, num("let n = 0; [1, , 3].map(() => n++); n"));
        assertFalse(bool("1 in [1, , 3].map(x => x)"));
    }

    // Searching by value never matches a hole
    @Test
    public void test_index_lookups_skip_holes() {
        assertEquals(-1, num("[1, , 3].indexOf(undefined)"));
        assertEquals(-1, num("[1, , 3].lastIndexOf(undefined)"));
        assertEquals(1, num("[1, undefined, 3].indexOf(undefined)"));
    }

    // join and toString render a hole as empty
    @Test
    public void test_join_renders_holes_as_empty() {
        assertEquals("1--3", str("[1, , 3].join('-')"));
        assertEquals("1,,3", str("[1, , 3].toString()"));
    }

    // sort moves holes to the end
    @Test
    public void test_sort_moves_holes_last() {
        assertEquals("[1,3,null]", str("const h = [3, , 1]; h.sort(); JSON.stringify(h)"));
        assertEquals(3, num("const h = [3, , 1]; h.sort(); h.length"));
    }

    // A hole serialises as JSON null
    @Test
    public void test_json_stringify_renders_holes_as_null() {
        assertEquals("[1,null,3]", str("JSON.stringify([1, , 3])"));
    }

    // `in` reports a hole as absent
    @Test
    public void test_in_operator_reports_hole_as_absent() {
        assertFalse(bool("1 in [1, , 3]"));
        assertTrue(bool("0 in [1, , 3]"));
        assertTrue(bool("1 in [1, undefined, 3]"));
    }

    // Spread turns holes into real undefined elements
    @Test
    public void test_spread_fills_holes() {
        assertEquals(3, num("[...[1, , 3]].length"));
        assertInstanceOf(JsUndefined.class, Interpreter.run("[...[1, , 3]][1]"));
        assertTrue(bool("1 in [...[1, , 3]]"));
    }

    // Destructuring a hole yields undefined and honours a default
    @Test
    public void test_destructuring_a_hole() {
        assertInstanceOf(JsUndefined.class, Interpreter.run("const [, b] = [1, , 3]; b"));
        assertEquals(9, num("const [, b = 9] = [1, , 3]; b"));
    }

    // Assigning over a hole makes the index present again
    @Test
    public void test_overwriting_a_hole() {
        assertTrue(bool("const h = [1, , 3]; h[1] = 2; 1 in h"));
        assertEquals(2, num("const h = [1, , 3]; h[1] = 2; h[1]"));
    }
}
