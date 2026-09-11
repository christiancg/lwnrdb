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

    // A plain function's instances reach Object.prototype through the function's own (lazily
    // created) `prototype` object, which never gets its own [[Prototype]] wired to Object.prototype
    // at creation - the walk itself must still resolve it as the realm type-default, not stop short.
    @Test
    public void test_instanceof_plain_function_instance_reaches_object_prototype() {
        assertTrue(bool("function F(){}; new F() instanceof Object"));
        assertFalse(bool("function F(){}; new F() instanceof Function"));
    }

    // %Function.prototype% is itself callable per spec (IsCallable is true, though calling it is a
    // no-op) even though it is a plain JsObject, not a JsFunction/JsNativeFunction - so it must not
    // hit the generic "not callable" TypeError; OrdinaryHasInstance's own steps take over from there.
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

    // instanceof walks the class heritage chain
    @Test
    public void test_instanceof() {
        assertTrue(bool("class A {} new A() instanceof A"));
        assertTrue(bool("class A {} class B extends A {} new B() instanceof A"));
        assertFalse(bool("class A {} class B {} new B() instanceof A"));
        assertFalse(bool("class A {} 5 instanceof A"));
    }

    // instanceof with a non-callable right-hand side throws a TypeError
    @Test
    public void test_instanceof_non_callable_rhs() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("5 instanceof 5"));
    }

    // Without a hasInstance override, ordinary heritage-based instanceof still applies
    @Test
    public void test_instanceof_without_override() {
        assertTrue(bool("class A {} class B extends A {} new B() instanceof A"));
        assertFalse(bool("class A {} class B {} new B() instanceof A"));
    }

    // instanceof against a bound function delegates to the bound target
    @Test
    public void test_instanceof_bound_function() {
        assertTrue(bool("function F() {} const B = F.bind(null); new F() instanceof B"));
    }

    // instanceof against a native function with no prototype is false
    @Test
    public void test_instanceof_native_function_without_prototype() {
        assertFalse(bool("5 instanceof parseInt"));
    }

    // instanceof against an unrelated native constructor's prototype is false
    @Test
    public void test_instanceof_native_prototype_mismatch() {
        assertFalse(bool("[] instanceof Map"));
    }

    // A native-heritage class's instances are real JsObjects (klass + proto linked to the class's
    // own prototype), so instanceof must not fall back to the shared native intrinsic prototype -
    // that would make every plain `new Set()` look like an instance of any `class extends Set`
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
