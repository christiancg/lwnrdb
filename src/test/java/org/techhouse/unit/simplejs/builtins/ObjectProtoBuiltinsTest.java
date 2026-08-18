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
        // valueOf is `Return ? ToObject(this value)`: a primitive receiver comes back boxed, not
        // the bare primitive, so it is never === to itself and typeof reports "object".
        assertFalse(bool("Object.prototype.valueOf.call(5) === 5"));
        assertTrue(bool("typeof Object.prototype.valueOf.call(true) === 'object'"));
        assertTrue(bool("Object.prototype.valueOf.call('x').valueOf() === 'x'"));
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

    // toLocaleString delegates to the receiver's own toString
    @Test
    public void test_to_locale_string() {
        assertEquals("[object Object]", strOf("({}).toLocaleString()"));
        assertEquals("custom", strOf("({toString() { return 'custom'; }}).toLocaleString()"));
    }

    // toLocaleString on null or undefined is a TypeError
    @Test
    public void test_to_locale_string_nullish_receiver_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("Object.prototype.toLocaleString.call(null)"));
    }

    // __defineGetter__ installs an enumerable, configurable accessor
    @Test
    public void test_define_getter() {
        assertTrue(bool("const o = {}; o.__defineGetter__('x', function () { return 42; }); o.x === 42"));
        assertTrue(bool("const o = {}; o.__defineGetter__('x', function () {}); "
                + "const d = Object.getOwnPropertyDescriptor(o, 'x'); "
                + "d.enumerable === true && d.configurable === true && d.set === undefined"));
    }

    // __defineSetter__ installs the setter side without disturbing an existing getter
    @Test
    public void test_define_setter() {
        assertTrue(bool("const o = {}; let seen; o.__defineSetter__('x', function (v) { seen = v; }); "
                + "o.x = 7; seen === 7"));
        assertTrue(bool("const o = {}; o.__defineGetter__('x', function () { return 1; }); "
                + "o.__defineSetter__('x', function () {}); const d = Object.getOwnPropertyDescriptor(o, 'x'); "
                + "typeof d.get === 'function' && typeof d.set === 'function'"));
    }

    // a non-callable accessor argument is a TypeError, and the receiver is coerced first
    @Test
    public void test_define_accessor_non_callable_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("({}).__defineGetter__('x', 1)"));
        assertThrows(TypeErrorException.class, () -> Interpreter.run("({}).__defineSetter__('x', null)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.prototype.__defineGetter__.call(null, 'x', function () {})"));
    }

    // __lookupGetter__/__lookupSetter__ find an own accessor and walk the prototype chain for an
    // inherited one, reporting undefined for a data property
    @Test
    public void test_lookup_accessor() {
        assertTrue(bool("const g = function () {}; const o = {}; Object.defineProperty(o, 'x', {get: g}); "
                + "o.__lookupGetter__('x') === g"));
        assertTrue(bool("const s = function () {}; const p = {}; Object.defineProperty(p, 'x', {set: s}); "
                + "const o = Object.create(p); o.__lookupSetter__('x') === s"));
        assertTrue(bool("({x: 1}).__lookupGetter__('x') === undefined"));
        assertTrue(bool("({}).__lookupGetter__('missing') === undefined"));
    }

    // the lookups coerce a non-object receiver rather than rejecting it, but still reject a nullish one
    @Test
    public void test_lookup_accessor_receiver() {
        assertTrue(bool("Object.prototype.__lookupGetter__.call(1, 'x') === undefined"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.prototype.__lookupSetter__.call(undefined, 'x')"));
    }

    // __proto__ reads and writes the receiver's [[Prototype]]
    @Test
    public void test_proto_accessor() {
        assertTrue(bool("const p = {}; const o = Object.create(p); o.__proto__ === p"));
        assertTrue(bool("const p = {}; const o = {}; o.__proto__ = p; Object.getPrototypeOf(o) === p"));
        assertTrue(bool("const o = Object.create({}); o.__proto__ = null; Object.getPrototypeOf(o) === null"));
    }

    // a cyclic assignment is rejected
    @Test
    public void test_proto_cycle_rejected() {
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("const a = {}; const b = Object.create(a); a.__proto__ = b;"));
    }

    // a real change on a non-extensible object is a TypeError, while a no-op assignment is allowed
    @Test
    public void test_proto_non_extensible() {
        assertTrue(bool("const p = {}; const o = Object.create(p); Object.preventExtensions(o); "
                + "o.__proto__ = p; Object.getPrototypeOf(o) === p"));
        assertThrows(TypeErrorException.class, () -> Interpreter
                .run("const p = {}; const o = Object.create(p); Object.preventExtensions(o); o.__proto__ = {};"));
    }

    // a value that is neither an object nor null is silently ignored
    @Test
    public void test_proto_non_object_value_ignored() {
        assertTrue(
                bool("const p = {}; const o = Object.create(p); o.__proto__ = 1; " + "Object.getPrototypeOf(o) === p"));
    }

    // the accessor pair is non-enumerable, configurable and carries the spec function names
    @Test
    public void test_proto_property_descriptor() {
        assertTrue(bool("const d = Object.getOwnPropertyDescriptor(Object.prototype, '__proto__'); "
                + "d.enumerable === false && d.configurable === true && d.value === undefined"));
        assertEquals("get __proto__", strOf("Object.getOwnPropertyDescriptor(Object.prototype, '__proto__').get.name"));
        assertEquals("set __proto__", strOf("Object.getOwnPropertyDescriptor(Object.prototype, '__proto__').set.name"));
    }

    // the four Annex B accessors report their spec name and length
    @Test
    public void test_annex_b_accessor_metadata() {
        assertTrue(bool(
                "Object.prototype.__defineGetter__.length === 2 " + "&& Object.prototype.__defineSetter__.length === 2 "
                        + "&& Object.prototype.__lookupGetter__.length === 1 "
                        + "&& Object.prototype.__lookupSetter__.length === 1"));
        assertEquals("__defineGetter__", strOf("Object.prototype.__defineGetter__.name"));
        assertEquals("__lookupSetter__", strOf("Object.prototype.__lookupSetter__.name"));
    }

    // isPrototypeOf(V): step 1 (V must be an Object) runs before step 2 (ToObject(this value)), so
    // a non-object V short-circuits to false even when `this` is null/undefined, while an object V
    // with a null/undefined `this` throws.
    @Test
    public void test_is_prototype_of_argument_checked_before_this() {
        assertFalse(bool("Object.prototype.isPrototypeOf.call(null, 1)"));
        assertFalse(bool("Object.prototype.isPrototypeOf.call(undefined, 1)"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.prototype.isPrototypeOf.call(null, function() {})"));
        assertThrows(TypeErrorException.class,
                () -> Interpreter.run("Object.prototype.isPrototypeOf.call(undefined, {})"));
    }

    // a primitive `this` can never appear in an object's prototype chain, so it answers false
    // rather than throwing once V has been confirmed to be an object.
    @Test
    public void test_is_prototype_of_primitive_this_is_false() {
        assertFalse(bool("Object.prototype.isPrototypeOf.call(1, {})"));
    }

    // isPrototypeOf on a Proxy argument runs only its "getPrototypeOf" trap, not a raw internal
    // [[Prototype]] read (which a Proxy has none of).
    @Test
    public void test_is_prototype_of_over_a_proxy_argument() {
        assertTrue(bool("""
                const proxyProto = [];
                const handler = { getPrototypeOf(_t) { return proxyProto; } };
                const proxy = new Proxy({}, handler);
                proxyProto.isPrototypeOf(proxy)
                """));
    }

    // Object.prototype.toString's builtinTag fallback for Map/Set/WeakMap/WeakSet/Promise/
    // Generator/Symbol/BigInt is "Object" (ES2026 step 14 names only Array/Function/Error/
    // Boolean/Number/String/Date/RegExp) - their usual "[object Map]"-style name comes entirely
    // from a real, deletable @@toStringTag on the type's own prototype, consulted above brand().
    @Test
    public void test_to_string_builtin_tag_fallback_is_object_once_the_real_tag_is_gone() {
        assertEquals("[object Object]", strOf("""
                const m = new Map();
                delete Map.prototype[Symbol.toStringTag];
                Object.prototype.toString.call(m)
                """));
        assertEquals("[object Object]", strOf("""
                delete Symbol.prototype[Symbol.toStringTag];
                Object.prototype.toString.call(Symbol('d'))
                """));
        assertEquals("[object Object]", strOf("""
                const p = Promise.resolve();
                delete Promise.prototype[Symbol.toStringTag];
                Object.prototype.toString.call(p)
                """));
    }
}
