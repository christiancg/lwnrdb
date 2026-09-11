package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class IntrinsicsArrayTest {
    private static String run(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    // Array.prototype methods accept a primitive string receiver, reading it through ToObject
    @Test
    public void test_array_prototype_accepts_array_likes() {
        assertEquals("a-b", run("Array.prototype.join.call('ab', '-')"));
    }

    // Array.prototype methods accept a plain array-like receiver
    @Test
    public void test_array_method_on_plain_array_like() {
        assertEquals("[2,4]", run("JSON.stringify(Array.prototype.map.call({length: 2, 0: 1, 1: 2}, x => x * 2))"));
    }

    // reduce folds over a plain array-like
    @Test
    public void test_array_reduce_on_plain_array_like() {
        assertEquals("6",
                run("String(Array.prototype.reduce.call({length: 3, 0: 1, 1: 2, 2: 3}, (a, b) => a + b, 0))"));
    }

    // a missing index of an array-like is a hole, which join renders as empty
    @Test
    public void test_array_join_on_plain_array_like_with_holes() {
        assertEquals("a--c", run("Array.prototype.join.call({length: 3, 0: 'a', 2: 'c'}, '-')"));
    }

    // a backwards-walking method reads the array-like's indices in descending order
    @Test
    public void test_reverse_walking_array_method_on_plain_array_like() {
        assertEquals("cba",
                run("Array.prototype.reduceRight.call({length: 3, 0: 'a', 1: 'b', 2: 'c'}, (a, b) => a + b)"));
    }

    // a mutating method runs against a snapshot, so its effects are written back onto the real
    // array-like receiver (indices, removed trailing indices, and length) rather than discarded
    @Test
    public void test_mutating_array_method_writes_back_to_plain_array_like() {
        assertEquals("[1,1]", run("const o = {length: 0}; const n = Array.prototype.push.call(o, 1);"
                + " JSON.stringify([n, o.length])"));
        assertEquals("[\"c\",2,false]",
                run("const o = {0: 'a', 1: 'b', 2: 'c', length: 3};" + " const popped = Array.prototype.pop.call(o);"
                        + " JSON.stringify([popped, o.length, o.hasOwnProperty('2')])"));
        assertEquals("[3,2,1]", run("const o = {0: 1, 1: 2, 2: 3, length: 3}; Array.prototype.reverse.call(o);"
                + " JSON.stringify([o[0], o[1], o[2]])"));
    }

    // undefined/null still report an incompatible-receiver TypeError - ToObject rejects them
    // outright, unlike every other value (which ToObject always succeeds on).
    @Test
    public void test_array_method_on_null_or_undefined_still_throws() {
        final var error = assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Array.prototype.push.call(undefined)"));
        assertTrue(error.getMessage().contains("Array.prototype.push"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Array.prototype.push.call(null)"));
    }

    // a generic (non-mutating) Array method works on any plain object per spec, treating a missing
    // "length" as 0 (LengthOfArrayLike) rather than rejecting the receiver
    @Test
    public void test_non_mutating_array_method_on_lengthless_object_iterates_zero_elements() {
        assertEquals("[]", run("JSON.stringify(Array.prototype.map.call({}, x => x))"));
        assertEquals("true", run("String(Array.prototype.every.call({}, x => false))"));
    }

    // a subclass of ArrayBuffer wraps the produced primitive for ArrayBuffer.prototype methods
    @Test
    public void test_array_buffer_subclass_unwraps() {
        assertEquals(4,
                num("class Buf extends ArrayBuffer { constructor(n) { super(n); } } new Buf(4).slice(0).byteLength"));
    }

    // a subclass of a TypedArray wraps the produced primitive for %TypedArray%.prototype methods
    @Test
    public void test_typed_array_subclass_unwraps() {
        assertEquals(1, num("class T extends Int8Array { constructor(n) { super(n); } } new T(4).fill(1)[0]"));
    }
}
