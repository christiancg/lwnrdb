package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ArraySearchBuiltinsTest {
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
    public void test_find_throws_on_non_callable_predicate_even_on_empty_array() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].find(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1, 2, 3].find(null)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].map(1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].forEach('')"));
    }

    @Test
    public void test_includes_indexof() {
        assertTrue(bool("[1, 2, 3].includes(2)"));
        assertFalse(bool("[1, 2, 3].includes(9)"));
        assertEquals(1, num("['a', 'b', 'c'].indexOf('b')"));
        assertEquals(-1, num("[1].indexOf(9)"));
    }

    @Test
    public void test_find_variants() {
        assertEquals(1, num("[1, 2, 3].findIndex(x => x === 2)"));
        assertEquals(-1, num("[1, 2, 3].findIndex(x => x === 9)"));
        assertEquals(4, num("[1, 4, 2, 4, 3].findLast(x => x === 4)"));
        assertEquals(3, num("[1, 4, 2, 4, 3].findLastIndex(x => x === 4)"));
        assertEquals(3, num("[1, 2, 1, 2].lastIndexOf(2)"));
    }

    @Test
    public void test_find_and_flatmap_edges() {
        assertTrue(bool("[1, 2].findLast(x => x === 9) === undefined"));
        assertEquals(-1, num("[1, 2].findLastIndex(x => x === 9)"));
        assertEquals(-1, num("[1, 2].lastIndexOf()"));
        assertEquals("1,2", str("[1, 2].flatMap(x => x).join(',')"));
        assertEquals("4,2,3,4,5", str("let a = [1, 2, 3, 4, 5]; a.copyWithin(0, 3, 4); a.join(',')"));
    }

    @Test
    public void test_includes_same_value_zero() {
        assertTrue(bool("[NaN].includes(NaN)"));
        assertTrue(bool("[-0].includes(0)"));
        assertTrue(bool("[0].includes(-0)"));
    }

    @Test
    public void test_index_of_nan_unchanged() {
        assertEquals(-1, num("[NaN].indexOf(NaN)"));
        assertEquals(0, num("[-0].indexOf(0)"));
    }

    @Test
    public void test_includes_finds_hole_as_undefined() {
        assertTrue(bool("[,].includes(undefined)"));
        assertEquals(-1, num("[,].indexOf(undefined)"));
    }

    @Test
    public void test_includes_from_index() {
        assertFalse(bool("[1, 2].includes(1, 1)"));
        assertTrue(bool("[1, 2].includes(2, 1)"));
        assertTrue(bool("[1, 2, 3].includes(3, -1)"));
        assertFalse(bool("[1, 2, 3].includes(1, -1)"));
    }

    @Test
    public void test_includes_no_argument() {
        assertTrue(bool("[undefined].includes()"));
        assertFalse(bool("[1].includes()"));
    }

    @Test
    public void test_index_of_honours_from_index() {
        assertEquals(2, num("[1, 2, 1].indexOf(1, 1)"));
        assertEquals(-1, num("[1, 2, 1].indexOf(1, 3)"));
        assertEquals(2, num("[1, 2, 1].indexOf(1, -1)"));
        assertEquals(0, num("[1, 2, 1].indexOf(1, -9)"));
        assertEquals(-1, num("[1, 2, 1].indexOf(1, Infinity)"));
        assertEquals(0, num("[1, 2, 1].indexOf(1, 'one')"));
    }

    @Test
    public void test_last_index_of_honours_from_index() {
        assertEquals(1, num("[0, 1, 1].lastIndexOf(1, 1)"));
        assertEquals(2, num("[0, 1, 1].lastIndexOf(1)"));
        assertEquals(1, num("[0, 1, 1].lastIndexOf(1, -2)"));
        assertEquals(-1, num("[0, 1, 1].lastIndexOf(1, -9)"));
        assertEquals(-1, num("[0, 1, 1].lastIndexOf(1, -Infinity)"));
        assertEquals(2, num("[0, 1, 1].lastIndexOf(1, Infinity)"));
    }

    @Test
    public void test_index_write_reaches_an_inherited_setter() {
        assertEquals(1,
                num("let hits = 0;"
                        + " Object.defineProperty(Array.prototype, '0', {set(v) { hits++; }, configurable: true});"
                        + " const a = []; try { a.push(1); } catch (e) {} delete Array.prototype[0]; hits"));
    }
}
