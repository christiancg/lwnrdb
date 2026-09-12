package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;

public class InterpreterInstanceofTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    @Test
    public void test_instanceof_plain_function_instance_reaches_object_prototype() {
        assertTrue(bool("function F(){}; new F() instanceof Object"));
        assertFalse(bool("function F(){}; new F() instanceof Function"));
    }

    @Test
    public void test_instanceof_function_prototype_as_rhs() {
        assertFalse(bool("0 instanceof Function.prototype"));
        assertTrue(bool("""
                var getterCalled = false;
                Object.defineProperty(Function.prototype, 'prototype', {
                    get: function() { getterCalled = true; return Array.prototype; }
                });
                ([] instanceof Function.prototype) && getterCalled
                """));
    }

    @Test
    public void test_instanceof() {
        assertTrue(bool("class A {} new A() instanceof A"));
        assertTrue(bool("class A {} class B extends A {} new B() instanceof A"));
        assertFalse(bool("class A {} class B {} new B() instanceof A"));
        assertFalse(bool("class A {} 5 instanceof A"));
    }

    @Test
    public void test_instanceof_non_callable_rhs() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("5 instanceof 5"));
    }

    @Test
    public void test_instanceof_without_override() {
        assertTrue(bool("class A {} class B extends A {} new B() instanceof A"));
        assertFalse(bool("class A {} class B {} new B() instanceof A"));
    }

    @Test
    public void test_instanceof_bound_function() {
        assertTrue(bool("function F() {} const B = F.bind(null); new F() instanceof B"));
    }

    @Test
    public void test_instanceof_native_function_without_prototype() {
        assertFalse(bool("5 instanceof parseInt"));
    }

    @Test
    public void test_instanceof_native_prototype_mismatch() {
        assertFalse(bool("[] instanceof Map"));
    }

    @Test
    public void test_instanceof_with_native_heritage_does_not_match_every_native_instance() {
        final var setup = "class MySet extends Set {} ";
        assertFalse(bool(setup + "new Set() instanceof MySet"));
        assertTrue(bool(setup + "new MySet() instanceof MySet"));
        assertTrue(bool(setup + "new MySet() instanceof Set"));
        assertTrue(bool(setup + "Object.getPrototypeOf(new MySet()) === MySet.prototype"));
        assertFalse(bool(setup + "Object.getPrototypeOf(new MySet()) === Set.prototype"));
    }
}
