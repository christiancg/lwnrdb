package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;

public class ErrorBuiltinsTest {
    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // Error.isError is true for error objects made by any error constructor
    @Test
    public void test_is_error_true() {
        assertTrue(bool("Error.isError(new Error('x'))"));
        assertTrue(bool("Error.isError(new TypeError('x'))"));
        assertTrue(bool("Error.isError(new RangeError('x'))"));
        assertTrue(bool("Error.isError(new AggregateError([], 'x'))"));
    }

    // a caught runtime error surfaces as a branded error object
    @Test
    public void test_is_error_caught_runtime_error() {
        assertTrue(bool("let ok = false; try { null.x; } catch (e) { ok = Error.isError(e); } ok"));
    }

    // Error.isError is false for plain objects and non-objects, even error-shaped ones
    @Test
    public void test_is_error_false() {
        assertFalse(bool("Error.isError({name: 'Error', message: 'x'})"));
        assertFalse(bool("Error.isError('Error')"));
        assertFalse(bool("Error.isError(5)"));
        assertFalse(bool("Error.isError()"));
    }
}
