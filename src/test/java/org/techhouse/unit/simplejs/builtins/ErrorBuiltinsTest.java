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

    // The options bag supplies a cause
    @Test
    public void test_error_cause() {
        assertTrue(bool("new Error('m', { cause: 'c' }).cause === 'c'"));
        assertTrue(bool("new Error('m').cause === undefined"));
        assertTrue(bool("new Error('m', {}).cause === undefined"));
    }

    // A synthetic single-frame stack is present
    @Test
    public void test_error_stack() {
        assertTrue(bool("typeof new Error('m').stack === 'string'"));
        assertTrue(bool("new Error('m').stack.indexOf('Error: m') === 0"));
    }

    // Error.prototype.toString omits the separator when the message is empty
    @Test
    public void test_error_to_string() {
        assertTrue(bool("new Error('m').toString() === 'Error: m'"));
        assertTrue(bool("new Error().toString() === 'Error'"));
        assertTrue(bool("new TypeError('t').toString() === 'TypeError: t'"));
        assertTrue(bool("new RangeError('').toString() === 'RangeError'"));
    }

    // Prototype-linked error objects are still branded and keep their identity
    @Test
    public void test_error_identity() {
        assertTrue(bool("Error.isError(new TypeError('x'))"));
        assertTrue(bool("new TypeError('x') instanceof TypeError"));
        assertTrue(bool("new TypeError('x') instanceof Error"));
        assertFalse(bool("new Error('x') instanceof TypeError"));
        assertTrue(bool("new SuppressedError(1, 2, 'm') instanceof Error"));
        assertTrue(bool("new AggregateError([], 'm') instanceof Error"));
        assertTrue(bool("Error.prototype.constructor === Error"));
    }
}
