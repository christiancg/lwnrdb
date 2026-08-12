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

    // hasOwnProperty answers a symbol key from the symbol storage
    @Test
    public void test_has_own_property_with_symbol_key() {
        assertTrue(bool("const s = Symbol('k'); const o = {}; o[s] = 1; Object.prototype.hasOwnProperty.call(o, s)"));
    }

    // an absent symbol key reports false rather than throwing
    @Test
    public void test_has_own_property_with_absent_symbol_key() {
        assertFalse(bool("Object.prototype.hasOwnProperty.call({}, Symbol('z'))"));
        assertFalse(bool("Object.prototype.hasOwnProperty.call(1, Symbol('z'))"));
    }

    // propertyIsEnumerable accepts a symbol key
    @Test
    public void test_property_is_enumerable_with_symbol_key() {
        assertTrue(bool(
                "const s = Symbol('k'); const o = {}; o[s] = 1; Object.prototype.propertyIsEnumerable.call(o, s)"));
        assertFalse(bool("Object.prototype.propertyIsEnumerable.call({}, Symbol('z'))"));
    }

    // Object.hasOwn accepts a symbol key
    @Test
    public void test_object_has_own_with_symbol_key() {
        assertTrue(bool("const s = Symbol('k'); const o = {}; o[s] = 1; Object.hasOwn(o, s)"));
        assertFalse(bool("Object.hasOwn({}, Symbol('z'))"));
    }

    // a declared global is an own property of globalThis
    @Test
    public void test_has_own_property_on_global_this() {
        assertTrue(bool("var g = 1; Object.prototype.hasOwnProperty.call(globalThis, 'g')"));
    }

    // an undeclared name is not an own property of globalThis
    @Test
    public void test_has_own_property_on_global_this_absent() {
        assertFalse(bool("Object.prototype.hasOwnProperty.call(globalThis, 'notDeclaredAnywhere')"));
    }

    // hasOwnProperty and propertyIsEnumerable with no key argument report false
    @Test
    public void test_has_own_property_and_enumerable_no_args() {
        assertFalse(bool("({}).hasOwnProperty()"));
        assertFalse(bool("({a: 1}).propertyIsEnumerable()"));
    }

    // propertyIsEnumerable on a callable receiver reads its own enumerable property keys
    @Test
    public void test_property_is_enumerable_on_function() {
        assertTrue(bool("function f() {} f.x = 1; Object.prototype.propertyIsEnumerable.call(f, 'x')"));
        assertFalse(bool("function f() {} Object.prototype.propertyIsEnumerable.call(f, 'name')"));
    }

    // isPrototypeOf falls back to the intrinsic Object.prototype when a chain dead-ends at an explicit
    // null proto rather than reaching it naturally
    @Test
    public void test_is_prototype_of_falls_back_past_explicit_null_proto() {
        assertTrue(bool("let p = {}; Object.setPrototypeOf(p, null); let o = Object.create(p); "
                + "Object.prototype.isPrototypeOf(o)"));
    }

    // toString.call brands cover the remaining builtin types
    @Test
    public void test_to_string_call_more_brands() {
        assertEquals("[object Function]", strOf("Object.prototype.toString.call(Array.prototype.push)"));
        assertEquals("[object Function]", strOf("Object.prototype.toString.call(class {})"));
        assertEquals("[object BigInt]", strOf("Object.prototype.toString.call(1n)"));
        assertEquals("[object Symbol]", strOf("Object.prototype.toString.call(Symbol())"));
        assertEquals("[object Promise]", strOf("Object.prototype.toString.call(Promise.resolve())"));
        assertEquals("[object Generator]", strOf("function* g() {} Object.prototype.toString.call(g())"));
        assertEquals("[object AsyncGenerator]", strOf("async function* g() {} Object.prototype.toString.call(g())"));
        assertEquals("[object ArrayBuffer]", strOf("Object.prototype.toString.call(new ArrayBuffer(4))"));
        assertEquals("[object DataView]", strOf("Object.prototype.toString.call(new DataView(new ArrayBuffer(4)))"));
        assertEquals("[object Uint8Array]", strOf("Object.prototype.toString.call(new Uint8Array(2))"));
        assertEquals("[object global]", strOf("Object.prototype.toString.call(globalThis)"));
        assertEquals("[object Array]", strOf("Object.prototype.toString.call(new Proxy([], {}))"));
    }

    // a primitive-wrapper receiver (created via new String/Number/Boolean) reports its boxed brand
    @Test
    public void test_to_string_call_primitive_wrapper_brands() {
        assertEquals("[object Number]", strOf("Object.prototype.toString.call(new Number(1))"));
        assertEquals("[object String]", strOf("Object.prototype.toString.call(new String('a'))"));
        assertEquals("[object Boolean]", strOf("Object.prototype.toString.call(new Boolean(true))"));
    }
}
