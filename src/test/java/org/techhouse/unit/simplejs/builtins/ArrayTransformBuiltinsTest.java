package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class ArrayTransformBuiltinsTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // map/filter/reduce transform elements
    @Test
    public void test_map_filter_reduce() {
        assertEquals("2,4,6", str("[1, 2, 3].map(x => x * 2).join(',')"));
        assertEquals("2,4", str("[1, 2, 3, 4].filter(x => x % 2 === 0).join(',')"));
        assertEquals(10, num("[1, 2, 3, 4].reduce((a, b) => a + b, 0)"));
        assertEquals(24, num("[1, 2, 3, 4].reduce((a, b) => a * b)"));
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

    // reduceRight folds from the right, with and without an initial value
    @Test
    public void test_reduce_right() {
        assertEquals("3,2,1", str("['1', '2', '3'].reduceRight((a, b) => a + ',' + b)"));
        assertEquals(6, num("[1, 2, 3].reduceRight((a, b) => a + b, 0)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[].reduceRight((a, b) => a + b)"));
    }

    // flatMap maps then flattens one level
    @Test
    public void test_flatmap() {
        assertEquals("1,1,2,2", str("[1, 2].flatMap(x => [x, x]).join(',')"));
        assertEquals("1,2,3,4", str("[[1, 2], [3, 4]].flatMap(x => x).join(',')"));
    }

    // fill and copyWithin mutate in place
    @Test
    public void test_fill_copywithin() {
        assertEquals("0,9,9,3", str("let a = [0, 1, 2, 3]; a.fill(9, 1, 3); a.join(',')"));
        assertEquals("9,9,9,9", str("[1, 2, 3, 4].fill(9).join(',')"));
        assertEquals("4,5,3,4,5", str("let a = [1, 2, 3, 4, 5]; a.copyWithin(0, 3); a.join(',')"));
    }

    // reverse and at
    @Test
    public void test_reverse_at() {
        assertEquals("3,2,1", str("[1, 2, 3].reverse().join(',')"));
        assertEquals(3, num("[1, 2, 3].at(-1)"));
        assertEquals(1, num("[1, 2, 3].at(0)"));
        assertTrue(bool("[1, 2, 3].at(9) === undefined"));
    }

    // Array.from honours the mapfn's thisArg
    @Test
    public void test_array_from_map_this_arg() {
        assertEquals(5, num("let o = {v: 5}; Array.from([1], function() { return this.v; }, o)[0]"));
    }

    // toReversed returns a reversed copy and leaves the original untouched
    @Test
    public void test_to_reversed() {
        assertEquals("3,2,1", str("[1, 2, 3].toReversed().join(',')"));
        assertEquals("1,2,3", str("let a = [1, 2, 3]; a.toReversed(); a.join(',')"));
    }

    // toSorted returns a sorted copy without mutating the original
    @Test
    public void test_to_sorted() {
        assertEquals("1,2,3", str("[3, 1, 2].toSorted().join(',')"));
        assertEquals("3,1,2", str("let a = [3, 1, 2]; a.toSorted(); a.join(',')"));
        assertEquals("3,2,1", str("[1, 2, 3].toSorted((x, y) => y - x).join(',')"));
    }

    // toSpliced returns a copy with the splice applied, leaving the original intact
    @Test
    public void test_to_spliced() {
        assertEquals("1,9,4", str("[1, 2, 3, 4].toSpliced(1, 2, 9).join(',')"));
        assertEquals("1,2,3,4", str("let a = [1, 2, 3, 4]; a.toSpliced(1, 2, 9); a.join(',')"));
    }

    // the default comparator compares the ToString of each element, and undefined sorts last
    @Test
    public void test_sort_default_comparator_uses_to_string() {
        assertEquals("1,10,9", str("[10, 9, 1].sort().join(',')"));
        assertEquals("1,10,9,", str("[10, 9, undefined, 1].sort().join(',')"));
        assertEquals("1,2,,3", str("const a = [2, , 1]; a.sort(); a.join(',') + ',' + a.length"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("[1, 2].sort('x')"));
    }

    // splice shifts the tail in both directions and reports the removed elements
    @Test
    public void test_splice_grows_and_shrinks() {
        assertEquals("1,9,10,2,3", str("const a = [1, 2, 3]; a.splice(1, 0, 9, 10); a.join(',')"));
        assertEquals("1,3", str("const a = [1, 2, 3]; a.splice(1, 1); a.join(',')"));
        assertEquals("2,3", str("[1, 2, 3].splice(1).join(',')"));
        assertEquals("", str("[1, 2, 3].splice().join(',')"));
        assertEquals("1,9,10,3", str("const o = {0: 1, 1: 2, 2: 3, length: 3};"
                + " Array.prototype.splice.call(o, 1, 1, 9, 10); Array.prototype.join.call(o, ',')"));
    }

    // toSpliced builds the copy from the head, the insertions and the tail
    @Test
    public void test_to_spliced_variants() {
        assertEquals("1,9,10,2,3", str("[1, 2, 3].toSpliced(1, 0, 9, 10).join(',')"));
        assertEquals("1", str("[1, 2, 3].toSpliced(1).join(',')"));
        assertEquals("1,2,3", str("[1, 2, 3].toSpliced().join(',')"));
    }

    // reverse swaps a hole with a value, so the hole moves rather than becoming undefined
    @Test
    public void test_reverse_moves_holes() {
        assertTrue(bool("const a = [, 1]; a.reverse(); a[0] === 1 && !a.hasOwnProperty('1')"));
        assertEquals("3,2,1", str("const o = {0: 1, 1: 2, 2: 3, length: 3};"
                + " Array.prototype.reverse.call(o); Array.prototype.join.call(o, ',')"));
    }

    // the separator and the elements coerce through ToPrimitive, not a bare toString
    @Test
    public void test_join_coerces_the_separator() {
        assertEquals("1foo2", str("[1, 2].join({toString: () => 'foo'})"));
        assertEquals("1bar2", str("[1, 2].join({toString: undefined, valueOf: () => 'bar'})"));
        assertEquals("102", str("[1, 2].join(0)"));
    }

    // flat flattens a proxy whose target is an array, because it asks IsArray rather than the type
    @Test
    public void test_flat_flattens_through_a_proxy() {
        assertEquals("1,2,3", str("[1, new Proxy([2, 3], {})].flat().join(',')"));
    }
}
