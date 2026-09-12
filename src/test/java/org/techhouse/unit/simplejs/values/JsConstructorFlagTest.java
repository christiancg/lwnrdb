package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsUndefined;

public class JsConstructorFlagTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    private static JsFunction function(boolean arrow, boolean async, boolean generator) {
        return new JsFunction("f", List.of(), null, arrow, false, async, generator, Environment.global());
    }

    @Test
    public void test_native_function_defaults_to_non_constructor() {
        final var native1 = new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance());
        assertFalse(native1.isConstructor());
        assertFalse(InterpreterUtils.isConstructor(native1));
    }

    @Test
    public void test_marked_native_is_constructor() {
        final var native1 = new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance());
        native1.markConstructor();
        assertTrue(native1.isConstructor());
        assertTrue(InterpreterUtils.isConstructor(native1));
    }

    @Test
    public void test_arrow_method_generator_async_are_not_constructors() {
        assertFalse(function(true, false, false).isConstructor());
        assertFalse(function(false, true, false).isConstructor());
        assertFalse(function(false, false, true).isConstructor());
        final var method = function(false, false, false);
        method.markMethod();
        assertFalse(method.isConstructor());
    }

    @Test
    public void test_plain_function_and_class_are_constructors() {
        assertTrue(function(false, false, false).isConstructor());
        assertTrue(bool("function isCtor(f) { try { Reflect.construct(function () {}, [], f); return true; }"
                + " catch (e) { return false; } }" + "class C {} isCtor(C)"));
    }

    @Test
    public void test_bound_inherits_from_target() {
        assertTrue(bool("function isCtor(f) { try { Reflect.construct(function () {}, [], f); return true; }"
                + " catch (e) { return false; } }" + "isCtor(function () {}.bind(null))"));
        assertFalse(bool("function isCtor(f) { try { Reflect.construct(function () {}, [], f); return true; }"
                + " catch (e) { return false; } }" + "isCtor(Math.max.bind(null))"));
    }

    @Test
    public void test_proxy_delegates_to_target() {
        final var plain = new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance());
        assertFalse(new JsProxy(plain, new JsObject()).isConstructor());
        plain.markConstructor();
        assertTrue(new JsProxy(plain, new JsObject()).isConstructor());
        assertTrue(new JsProxy(new JsProxy(plain, new JsObject()), new JsObject()).isConstructor());
    }

    @Test
    public void test_script_assigned_prototype_does_not_confer_constructorness() {
        final var native1 = new JsNativeFunction("f", (_, _) -> JsUndefined.getInstance());
        native1.setPrototype(new JsObject());
        assertFalse(native1.isConstructor());
        assertFalse(bool("function isCtor(f) { try { Reflect.construct(function () {}, [], f); return true; }"
                + " catch (e) { return false; } }" + "Array.from.prototype = {}; isCtor(Array.from)"));
    }
}
