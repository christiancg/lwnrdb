package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNumber;

public class FunctionProgramTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static boolean bool(String source) {
        return ((JsBoolean) Interpreter.run(source)).getValue();
    }

    // A plain-function instance is an instanceof its constructor
    @Test
    public void test_instanceof_plain_function() {
        assertTrue(bool("function F(){ this.x = 1; } new F() instanceof F"));
    }

    // A plain object is not an instanceof an unrelated constructor
    @Test
    public void test_instanceof_plain_function_false() {
        assertFalse(bool("function F(){} ({}) instanceof F"));
    }

    // Methods added to a constructor's prototype resolve through the instance proto chain
    @Test
    public void test_prototype_method_resolution() {
        final var source = """
                function F(){ this.x = 10; }
                F.prototype.doubled = function() { return this.x * 2; };
                new F().doubled()
                """;
        assertEquals(20, num(source));
    }

    // A constructed instance's constructor property points back at the function
    @Test
    public void test_prototype_constructor_back_reference() {
        assertTrue(bool("function F(){} new F().constructor === F"));
    }

    // new on a bound function constructs the underlying target with bound args applied
    @Test
    public void test_bound_function_new() {
        final var source = """
                function Point(x, y){ this.x = x; this.y = y; }
                const g = Point.bind(null, 3);
                const p = new g(4);
                p.x + p.y
                """;
        assertEquals(7, num(source));
    }

    // A bound-function instance is still an instanceof the original target
    @Test
    public void test_bound_function_new_instanceof() {
        final var source = """
                function Point(x){ this.x = x; }
                const g = Point.bind(null, 3);
                new g() instanceof Point
                """;
        assertTrue(bool(source));
    }

    // Bound this is ignored when a bound function is used as a constructor
    @Test
    public void test_bound_function_new_ignores_bound_this() {
        final var source = """
                function F(){ this.here = true; }
                const g = F.bind({ here: false });
                new g().here
                """;
        assertTrue(bool(source));
    }

    // A tagged template in new-callee position evaluates the tag then constructs its result
    @Test
    public void test_tagged_template_in_new_position() {
        final var source = """
                function make(){ return function C(){ this.tagged = true; }; }
                function tag(){ return make(); }
                new tag`hello`().tagged
                """;
        assertTrue(bool(source));
    }

    // Mapped arguments: writing arguments[0] aliases the named simple parameter
    @Test
    public void test_mapped_arguments_index_to_param() {
        assertEquals(9, num("function f(a){ arguments[0] = 9; return a; } f(1)"));
    }

    // Mapped arguments: writing the named parameter aliases arguments[0]
    @Test
    public void test_mapped_arguments_param_to_index() {
        assertEquals(9, num("function f(a){ a = 9; return arguments[0]; } f(1)"));
    }

    // A default parameter makes the arguments object unmapped, so there is no aliasing
    @Test
    public void test_unmapped_arguments_with_default_param() {
        assertEquals(1, num("function f(a = 0){ arguments[0] = 9; return a; } f(1)"));
    }

    // A rest parameter makes the arguments object unmapped, so there is no aliasing
    @Test
    public void test_unmapped_arguments_with_rest_param() {
        assertEquals(1, num("function f(...a){ arguments[0] = 9; return a[0]; } f(1)"));
    }

    // arguments.length counts the passed arguments, extra args beyond params included
    @Test
    public void test_arguments_length_counts_passed() {
        assertEquals(3, num("function f(a){ return arguments.length; } f(1, 2, 3)"));
    }

    // Extra arguments beyond the mapped parameters are readable but not aliased
    @Test
    public void test_arguments_extra_args_not_mapped() {
        assertEquals(5, num("function f(a){ arguments[1] = 5; return arguments[1]; } f(1, 2)"));
    }

    // arguments is iterable with for-of, reflecting aliased values
    @Test
    public void test_arguments_for_of() {
        final var source = """
                function f(a, b){
                    a = 10;
                    let sum = 0;
                    for (const x of arguments) { sum += x; }
                    return sum;
                }
                f(1, 2)
                """;
        assertEquals(12, num(source));
    }

    // arguments spreads into an array
    @Test
    public void test_arguments_spread() {
        assertEquals(6, num("function f(){ return [...arguments].reduce((a, b) => a + b, 0); } f(1, 2, 3)"));
    }
}
