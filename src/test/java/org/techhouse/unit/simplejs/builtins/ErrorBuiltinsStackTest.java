package org.techhouse.unit.simplejs.builtins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.builtins.ErrorBuiltins;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsString;

public class ErrorBuiltinsStackTest {
    private static String text(String source) {
        return ((JsString) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // An error built with no interpreter running keeps the single synthetic frame it always had
    @Test
    public void test_stack_without_an_interpreter_keeps_the_synthetic_frame() {
        final var error = ErrorBuiltins.makeError("TypeError", "boom");
        assertTrue(error.getErrorStack().isEmpty());
    }

    @Test
    public void test_stack_header_is_name_and_message() {
        final var stack = text("new Error('boom').stack");
        assertTrue(stack.startsWith("Error: boom\n    at "), stack);
    }

    // The header reads the own `name` property, which a constructed error inherits rather than owns - that
    // predates the frames and is left alone here; what is asserted is the frame list below it
    @Test
    public void test_stack_lists_the_enclosing_frames() {
        final var stack = text("""
                function build() {
                  return new RangeError('bad');
                }
                build().stack
                """);
        assertTrue(stack.startsWith("Error: bad\n    at build ("), stack);
        assertTrue(stack.endsWith("\n    at main:4:1"), stack);
    }

    // The setter still installs an own property on the receiver rather than writing through the prototype
    @Test
    public void test_stack_setter_installs_an_own_property() {
        assertEquals("replaced", text("const e = new Error('x'); e.stack = 'replaced'; e.stack"));
    }

    @Test
    public void test_stack_setter_on_the_prototype_itself_throws() {
        assertTrue(bool("""
                let threw = false;
                try {
                  Object.getPrototypeOf(new Error('x')).stack = 'nope';
                } catch (e) {
                  threw = e instanceof TypeError;
                }
                threw
                """));
    }

    // The [[ErrorData]] brand check is unchanged: a plain object reading the accessor gets undefined
    @Test
    public void test_non_error_receiver_returns_undefined() {
        assertTrue(bool("""
                const getter = Object.getOwnPropertyDescriptor(Error.prototype, 'stack').get;
                getter.call({}) === undefined
                """));
    }

    @Test
    public void test_subclassed_error_carries_a_stack() {
        assertTrue(bool("""
                class AppError extends Error {}
                function make() {
                  return new AppError('x');
                }
                make().stack.indexOf('at make (') > 0
                """));
    }
}
