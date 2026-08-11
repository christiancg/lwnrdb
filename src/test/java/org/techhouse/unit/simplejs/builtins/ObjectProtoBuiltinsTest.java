package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class ObjectProtoBuiltinsTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static String strOf(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static String str() {
        return ((JsString) Interpreter.run("({}).toString()")).getValue();
    }

    // toString on a plain object yields the default tag
    @Test
    public void test_to_string() {
        assertEquals("[object Object]", str());
    }

    // valueOf returns the object itself
    @Test
    public void test_value_of() {
        assertTrue(bool("let o = {}; o.valueOf() === o"));
    }

    // isPrototypeOf walks the prototype chain
    @Test
    public void test_is_prototype_of() {
        assertTrue(bool("let p = {}; let o = Object.create(p); p.isPrototypeOf(o)"));
        assertFalse(bool("let p = {}; let o = {}; p.isPrototypeOf(o)"));
        assertFalse(bool("({}).isPrototypeOf(5)"));
    }

    // propertyIsEnumerable reflects own-property presence
    @Test
    public void test_property_is_enumerable() {
        assertTrue(bool("({a: 1}).propertyIsEnumerable('a')"));
        assertFalse(bool("({a: 1}).propertyIsEnumerable('b')"));
    }

    // toString.call reports the receiver's brand for every builtin type
    @Test
    public void test_to_string_call_brands() {
        assertEquals("[object Array]", strOf("Object.prototype.toString.call([])"));
        assertEquals("[object Number]", strOf("Object.prototype.toString.call(1)"));
        assertEquals("[object String]", strOf("Object.prototype.toString.call('a')"));
        assertEquals("[object Boolean]", strOf("Object.prototype.toString.call(true)"));
        assertEquals("[object Function]", strOf("Object.prototype.toString.call(function () {})"));
        assertEquals("[object Date]", strOf("Object.prototype.toString.call(new Date(0))"));
        assertEquals("[object RegExp]", strOf("Object.prototype.toString.call(/x/)"));
        assertEquals("[object Error]", strOf("Object.prototype.toString.call(new Error('x'))"));
        assertEquals("[object Null]", strOf("Object.prototype.toString.call(null)"));
        assertEquals("[object Undefined]", strOf("Object.prototype.toString.call(undefined)"));
        assertEquals("[object Object]", strOf("Object.prototype.toString.call({})"));
        assertEquals("[object Map]", strOf("Object.prototype.toString.call(new Map())"));
        assertEquals("[object Set]", strOf("Object.prototype.toString.call(new Set())"));
    }

    // an arguments object reports its own brand
    @Test
    public void test_to_string_call_arguments() {
        assertEquals("[object Arguments]",
                strOf("function f() { return Object.prototype.toString.call(arguments); } f()"));
    }

    // a string Symbol.toStringTag still wins over the brand table
    @Test
    public void test_to_string_tag_override() {
        assertEquals("[object Custom]", strOf("Object.prototype.toString.call({[Symbol.toStringTag]: 'Custom'})"));
    }

    // hasOwnProperty accepts an array, string or number receiver
    @Test
    public void test_has_own_property_non_object_receivers() {
        assertTrue(bool("[1].hasOwnProperty(0)"));
        assertFalse(bool("[1].hasOwnProperty(1)"));
        assertTrue(bool("'ab'.hasOwnProperty(0)"));
        assertTrue(bool("'ab'.hasOwnProperty('length')"));
        assertFalse(bool("(1).hasOwnProperty(0)"));
    }

    // a getter-only own property is reported by hasOwnProperty
    @Test
    public void test_has_own_property_accessor() {
        assertTrue(bool("({get x() { return 1; }}).hasOwnProperty('x')"));
    }

    // hasOwnProperty on null or undefined is a TypeError
    @Test
    public void test_has_own_property_nullish_receiver_throws() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.prototype.hasOwnProperty.call(null, 'x')"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.prototype.hasOwnProperty.call(undefined, 'x')"));
    }

    // propertyIsEnumerable treats array indices as enumerable and length as not
    @Test
    public void test_property_is_enumerable_on_array() {
        assertTrue(bool("[1].propertyIsEnumerable(0)"));
        assertFalse(bool("[1].propertyIsEnumerable('length')"));
    }

    // isPrototypeOf reaches the intrinsic prototypes and terminates at Object.prototype
    @Test
    public void test_is_prototype_of_intrinsics() {
        assertTrue(bool("Array.prototype.isPrototypeOf([])"));
        assertTrue(bool("Object.prototype.isPrototypeOf([])"));
        assertTrue(bool("Object.prototype.isPrototypeOf({})"));
        assertFalse(bool("Array.prototype.isPrototypeOf({})"));
        assertFalse(bool("Object.prototype.isPrototypeOf(1)"));
    }

    // valueOf returns the receiver unchanged
    @Test
    public void test_value_of_returns_receiver() {
        assertTrue(bool("const o = {}; Object.prototype.valueOf.call(o) === o"));
        assertTrue(bool("Object.prototype.valueOf.call(5) === 5"));
    }
}
