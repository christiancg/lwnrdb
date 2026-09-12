package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class InterpreterClassInheritanceTest {
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
    public void test_extends_non_constructor() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("class A extends 5 {}"));
    }

    @Test
    public void test_class_prototype_is_object() {
        assertEquals("object", str("class E { m() {} } typeof E.prototype"));
        assertTrue(bool("class E {} E.prototype.constructor === E"));
        assertTrue(bool("class E {} Object.getPrototypeOf(new E()) === E.prototype"));
        assertTrue(bool("class E {} typeof Object.getPrototypeOf(new E()) === 'object'"));
    }

    @Test
    public void test_prototype_is_patchable() {
        assertEquals(2, num("class E { m() { return 1; } } E.prototype.m = function() { return 2; }; new E().m()"));
        assertEquals("undefined", str("class E { m() { return 1; } } delete E.prototype.m; typeof new E().m"));
        assertEquals(7, num("class E {} const e = new E(); E.prototype.extra = function() { return 7; }; e.extra()"));
    }

    @Test
    public void test_prototype_entries_not_enumerable() {
        assertEquals(0, num("class E { m() {} } Object.keys(E.prototype).length"));
        assertEquals("{}", str("class E { m() {} } JSON.stringify(new E())"));
        assertEquals("{\"a\":1}", str("class E { constructor() { this.a = 1; } m() {} } JSON.stringify(new E())"));
    }

    @Test
    public void test_prototype_heritage_chain() {
        final var setup = "class A { m() { return 'a'; } } class B extends A {} ";
        assertTrue(bool(setup + "Object.getPrototypeOf(B.prototype) === A.prototype"));
        assertEquals("a", str(setup + "new B().m()"));
        assertTrue(bool(setup + "new B() instanceof A"));
        assertEquals("ab", str(
                "class A { m() { return 'a'; } } class B extends A { m() { return super.m() + 'b'; } } new B().m()"));
    }

    @Test
    public void test_class_has_own_name_length_and_prototype_properties() {
        assertTrue(bool("class C {} Object.getOwnPropertyNames(C).includes('name')"));
        assertTrue(bool("class C {} Object.getOwnPropertyNames(C).includes('length')"));
        assertTrue(bool("class C {} Object.getOwnPropertyNames(C).includes('prototype')"));
        assertEquals("C", str("class C {} C.name"));
        assertEquals(0, num("class C {} C.length"));
        assertEquals(2, num("class C { constructor(a, b) {} } C.length"));
        assertEquals(1, num("class C { constructor(a, b = 1) {} } C.length"));
        assertEquals("Anon", str("const Anon = class {}; Anon.name"));
        assertTrue(bool("class C {} C.name = 'x'; C.name === 'C'"));
        assertTrue(bool("class C {} C.length = 9; C.length === 0"));
        assertFalse(bool("class C {} Object.getOwnPropertyDescriptor(C, 'prototype').configurable"));
    }

    @Test
    public void test_class_object_and_prototype_chain_to_the_right_parent() {
        assertTrue(bool("class C {} Object.getPrototypeOf(C) === Function.prototype"));
        assertTrue(bool("class C {} Object.getPrototypeOf(C.prototype) === Object.prototype"));
        assertTrue(bool("class A {} class B extends A {} Object.getPrototypeOf(B) === A"));
        assertTrue(bool("class A {} class B extends A {} Object.getPrototypeOf(B.prototype) === A.prototype"));
        assertTrue(bool("class C extends null {} Object.getPrototypeOf(C) === Function.prototype"));
    }

    @Test
    public void test_prototype_absent_on_methods_getters_setters() {
        final var source = """
                class C {
                    method() {}
                    get accessor() { return 1; }
                    set accessor(v) {}
                }
                var methodDesc = Object.getOwnPropertyDescriptor(C.prototype, 'method');
                var accessorDesc = Object.getOwnPropertyDescriptor(C.prototype, 'accessor');
                JSON.stringify([
                    'prototype' in methodDesc.value,
                    'prototype' in accessorDesc.get,
                    'prototype' in accessorDesc.set,
                    'prototype' in C
                ])
                """;
        assertEquals("[false,false,false,true]", str(source));
    }

    @Test
    public void test_derived_constructor_implicit_return_resolves_base_override() {
        final var source = """
                class Base { constructor(obj) { return obj; } }
                class C extends Base {
                    #val;
                    constructor(obj) { super(obj); this.#val = 42; }
                    static val(obj) { return obj.#val; }
                }
                var t = new C({});
                C.val(t)
                """;
        assertEquals(42, num(source));
    }
}
