package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class GenericReceiverProgramTest {
    private static double num() {
        return ((JsNumber) Interpreter.run("ArrayBuffer.prototype.slice.call(new ArrayBuffer(4), 1).byteLength")).getValue();
    }

    private static String str(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("Function.prototype[Symbol.hasInstance].call(Array, [])")).getValue();
    }

    private static void typeError(String source) {
        assertThrows(TypeErrorException.class, () -> Interpreter.run(source));
    }

    // An array method applied to a string reads its code units
    @Test
    public void test_array_join_over_a_string() {
        assertEquals("a,b", str("Array.prototype.join.call('ab')"));
    }

    // An array method applied to a typed array reads its elements
    @Test
    public void test_array_map_over_a_typed_array() {
        assertEquals("2,4", str("Array.prototype.map.call(new Int8Array([1, 2]), x => x * 2).join(',')"));
    }

    // An array method applied to the arguments object reads its indices
    @Test
    public void test_array_slice_over_arguments() {
        assertEquals("1,2", str("function f() { return Array.prototype.slice.call(arguments).join(','); } f(1, 2)"));
    }

    // An array method applied to a plain array-like reads length and indices
    @Test
    public void test_array_join_over_an_array_like() {
        assertEquals("a,b", str("Array.prototype.join.call({ length: 2, 0: 'a', 1: 'b' })"));
    }

    // A missing index of an array-like is treated as a hole
    @Test
    public void test_array_like_hole() {
        assertEquals("a,,b", str("Array.prototype.join.call({ length: 3, 0: 'a', 2: 'b' })"));
    }

    // An array method applied to a nullish receiver is a TypeError
    @Test
    public void test_array_method_rejects_null() {
        typeError("Array.prototype.join.call(null)");
    }

    // Spreading an array into an object literal keys it by index
    @Test
    public void test_object_spread_of_an_array() {
        assertEquals("0,1", str("Object.keys({ ...[1, 2] }).join(',')"));
    }

    // Spreading a string into an object literal keys it by index
    @Test
    public void test_object_spread_of_a_string() {
        assertEquals("a", str("({ ...'ab' })[0]"));
    }

    // getOwnPropertyNames of an array reports the indices and length
    @Test
    public void test_own_property_names_of_an_array() {
        assertEquals("0,1,length", str("Object.getOwnPropertyNames([1, 2]).join(',')"));
    }

    // Reflect.ownKeys of an array reports the indices and length
    @Test
    public void test_own_keys_of_an_array() {
        assertEquals("0,length", str("Reflect.ownKeys([1]).join(',')"));
    }

    // A Map method brand-checks its receiver
    @Test
    public void test_map_method_brand_check() {
        typeError("Map.prototype.get.call({}, 1)");
    }

    // A Set method brand-checks its receiver
    @Test
    public void test_set_method_brand_check() {
        typeError("Set.prototype.add.call({}, 1)");
    }

    // A WeakMap method brand-checks its receiver
    @Test
    public void test_weak_map_method_brand_check() {
        typeError("WeakMap.prototype.get.call({}, {})");
    }

    // A WeakSet method brand-checks its receiver
    @Test
    public void test_weak_set_method_brand_check() {
        typeError("WeakSet.prototype.add.call({}, {})");
    }

    // Map and Set carry their own toStringTag
    @Test
    public void test_map_and_set_tags() {
        assertEquals("Map:Set", str("Map.prototype[Symbol.toStringTag] + ':' + Set.prototype[Symbol.toStringTag]"));
    }

    // A typed array reports its constructor name as its toStringTag
    @Test
    public void test_typed_array_tag() {
        assertEquals("Int8Array:[object Int8Array]", str("""
                new Int8Array(1)[Symbol.toStringTag] + ':' + Object.prototype.toString.call(new Int8Array(1))
                """));
    }

    // The toStringTag getter reports undefined for a non-typed-array receiver
    @Test
    public void test_typed_array_tag_on_the_prototype() {
        assertEquals("undefined", str("String(Int8Array.prototype[Symbol.toStringTag])"));
    }

    // The typed array length accessor brand-checks its receiver
    @Test
    public void test_typed_array_length_accessor_brand_check() {
        typeError("Object.getOwnPropertyDescriptor(Int8Array.prototype, 'length').get.call({})");
    }

    // The typed array buffer accessor brand-checks its receiver
    @Test
    public void test_typed_array_buffer_accessor_brand_check() {
        typeError("Object.getOwnPropertyDescriptor(Int8Array.prototype, 'buffer').get.call({})");
    }

    // An ArrayBuffer method can be applied through its prototype
    @Test
    public void test_array_buffer_method_through_the_prototype() {
        assertEquals(3, num());
    }

    // An ArrayBuffer method brand-checks its receiver
    @Test
    public void test_array_buffer_method_brand_check() {
        typeError("ArrayBuffer.prototype.slice.call({}, 0)");
    }

    // A DataView method brand-checks its receiver
    @Test
    public void test_data_view_method_brand_check() {
        typeError("DataView.prototype.getInt8.call({}, 0)");
    }

    // Function.prototype[Symbol.hasInstance] performs the ordinary instanceof check
    @Test
    public void test_ordinary_has_instance() {
        assertTrue(bool());
    }

    // Function.prototype[Symbol.hasInstance] reports false for an unrelated value
    @Test
    public void test_ordinary_has_instance_negative() {
        assertEquals("false", str("String(Function.prototype[Symbol.hasInstance].call(Array, 1))"));
    }

    // Error.prototype.toString falls back to the default name and an empty message
    @Test
    public void test_error_to_string_defaults() {
        assertEquals("Error", str("Error.prototype.toString.call({})"));
    }

    // Error.prototype.toString joins an explicit name and message
    @Test
    public void test_error_to_string_with_name_and_message() {
        assertEquals("X: y", str("Error.prototype.toString.call({ name: 'X', message: 'y' })"));
    }

    // An object method applied to a primitive receiver still reports its brand
    @Test
    public void test_object_to_string_over_primitives() {
        assertEquals("[object Number]:[object Null]:[object Undefined]", str("""
                Object.prototype.toString.call(1) + ':' + Object.prototype.toString.call(null) + ':'
                        + Object.prototype.toString.call(undefined)
                """));
    }

    // hasOwnProperty works over an array-like receiver
    @Test
    public void test_has_own_property_over_an_array() {
        assertEquals("true,false", str("""
                Object.prototype.hasOwnProperty.call([1], '0') + ',' + Object.prototype.hasOwnProperty.call([1], '1')
                """));
    }
}
