package org.techhouse.unit.simplejs.builtins;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Array is callable as a constructor with a length or with elements
    @Test
    public void test_array_constructor() {
        assertEquals(3, num("Array(3).length"));
        assertEquals("1,2", str("Array(1, 2).join(',')"));
        assertTrue(bool("Array.isArray([1])"));
        assertFalse(bool("Array.isArray('x')"));
    }

    // map/filter/reduce transform elements
    @Test
    public void test_map_filter_reduce() {
        assertEquals("2,4,6", str("[1, 2, 3].map(x => x * 2).join(',')"));
        assertEquals("2,4", str("[1, 2, 3, 4].filter(x => x % 2 === 0).join(',')"));
        assertEquals(10, num("[1, 2, 3, 4].reduce((a, b) => a + b, 0)"));
        assertEquals(24, num("[1, 2, 3, 4].reduce((a, b) => a * b)"));
    }

    // find/some/every/forEach iterate with a predicate
    @Test
    public void test_predicates_and_foreach() {
        assertEquals(3, num("[1, 2, 3, 4].find(x => x > 2)"));
        assertTrue(bool("[1, 2, 3].some(x => x === 2)"));
        assertTrue(bool("[2, 4, 6].every(x => x % 2 === 0)"));
        assertEquals(6, num("let s = 0; [1, 2, 3].forEach(x => { s += x; }); s"));
    }

    // includes/indexOf use strict equality
    @Test
    public void test_includes_indexof() {
        assertTrue(bool("[1, 2, 3].includes(2)"));
        assertFalse(bool("[1, 2, 3].includes(9)"));
        assertEquals(1, num("['a', 'b', 'c'].indexOf('b')"));
        assertEquals(-1, num("[1].indexOf(9)"));
    }

    // slice/splice/concat/flat build new arrays
    @Test
    public void test_slice_splice_concat_flat() {
        assertEquals("2,3", str("[1, 2, 3, 4].slice(1, 3).join(',')"));
        assertEquals("3,4", str("[1, 2, 3, 4].slice(-2).join(',')"));
        assertEquals("2,3", str("let a = [1, 2, 3, 4]; a.splice(1, 2).join(',')"));
        assertEquals("1,9,4", str("let a = [1, 2, 3, 4]; a.splice(1, 2, 9); a.join(',')"));
        assertEquals("1,2,3", str("[1].concat([2, 3]).join(',')"));
        assertEquals("1,2,3,4", str("[1, [2, [3, 4]]].flat(2).join(',')"));
    }

    // push/pop/shift/unshift mutate and return the expected values
    @Test
    public void test_mutators() {
        assertEquals(3, num("let a = [1, 2]; a.push(3)"));
        assertEquals(3, num("let a = [1, 2, 3]; a.pop()"));
        assertEquals(1, num("let a = [1, 2, 3]; a.shift()"));
        assertEquals(3, num("let a = [2, 3]; a.unshift(1)"));
    }

    // sort orders by string by default and by a comparator when given
    @Test
    public void test_sort() {
        assertEquals("1,2,3", str("[3, 1, 2].sort().join(',')"));
        assertEquals("3,2,1", str("[1, 3, 2].sort((a, b) => b - a).join(',')"));
    }

    // reduce on an empty array without an initial value throws
    @Test
    public void test_reduce_empty_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].reduce((a, b) => a + b)"));
    }

    // calling a callback method without a function throws
    @Test
    public void test_missing_callback_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1].map()"));
    }
}
