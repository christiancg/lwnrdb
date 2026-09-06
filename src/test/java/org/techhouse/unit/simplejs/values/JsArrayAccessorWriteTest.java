package org.techhouse.unit.simplejs.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;

// Regression coverage for test262 built-ins/Object/defineProperty/15.2.3.6-4-243-2.js: writing
// through a getter-only accessor - whether it lives at an array index or at a plain named property
// on the array - must reject the write (throwing TypeError, since the engine is always-strict)
// rather than silently no-opping as if the assignment had succeeded.
public class JsArrayAccessorWriteTest {
    private static boolean bool() {
        return ((JsBoolean) Interpreter.run("""
                var arr = [];
                Object.defineProperty(arr, "1", { get: function() { return 3; }, configurable: true });
                try { arr[1] = 4; } catch (e) {}
                const d = Object.getOwnPropertyDescriptor(arr, "1");
                d.enumerable === false && d.configurable === true
                """)).getValue();
    }

    // Assigning to an array-index accessor property with only a getter must throw TypeError, and the
    // getter-backed value/attributes must be left untouched.
    @Test
    public void test_write_to_index_accessor_with_no_setter_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [];
                function getFunc() { return 3; }
                Object.defineProperty(arr, "1", { get: getFunc, configurable: true });
                arr[1] = 4;
                """));
    }

    @Test
    public void test_index_accessor_value_and_attributes_survive_rejected_write() {
        assertEquals(3, ((JsNumber) Interpreter.run("""
                var arr = [];
                function getFunc() { return 3; }
                Object.defineProperty(arr, "1", { get: getFunc, configurable: true });
                try { arr[1] = 4; } catch (e) {}
                arr[1]
                """)).getValue());
        assertTrue(bool());
    }

    // Same mechanism for a plain (non-index) named property defined directly on the array instance.
    @Test
    public void test_write_to_named_prop_accessor_with_no_setter_throws() {
        assertThrows(TypeErrorException.class, () -> Interpreter.run("""
                var arr = [];
                Object.defineProperty(arr, "foo", { get: function() { return 3; }, configurable: true });
                arr.foo = 4;
                """));
    }

    // A getter/setter pair on either kind of accessor still writes through normally.
    @Test
    public void test_write_to_accessor_with_setter_still_succeeds() {
        assertEquals(9, ((JsNumber) Interpreter.run("""
                var arr = [];
                var stored = 0;
                Object.defineProperty(arr, "1", {
                    get: function() { return stored; },
                    set: function(v) { stored = v * v; },
                    configurable: true
                });
                arr[1] = 3;
                arr[1]
                """)).getValue());
    }
}
