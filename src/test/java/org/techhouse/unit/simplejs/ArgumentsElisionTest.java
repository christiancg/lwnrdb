package org.techhouse.unit.simplejs;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.simplejs.SimpleJs;
import org.techhouse.simplejs.host.ResourceLimits;
import org.techhouse.simplejs.host.SimpleHostBindings;

public class ArgumentsElisionTest {
    private final SimpleJs simpleJs = new SimpleJs();

    private static SimpleHostBindings host() {
        return new SimpleHostBindings(new JsonObject(), null, null, ResourceLimits.unlimited());
    }

    private double number(String source) {
        final var result = simpleJs.run(source, host());
        assertFalse(result.isError(), () -> source + " -> " + result.getErrorMessage());
        return result.getValue().asJsonNumber().getValue().doubleValue();
    }

    private String string(String source) {
        final var result = simpleJs.run(source, host());
        assertFalse(result.isError(), () -> source + " -> " + result.getErrorMessage());
        return result.getValue().asJsonString().getValue();
    }

    @Test
    public void test_a_function_reading_arguments_still_gets_it() {
        assertEquals(3d, number("function f(){ return arguments.length; } return f(1,2,3);"));
        assertEquals(7d, number("function f(){ return arguments[0] + arguments[1]; } return f(3,4);"));
    }

    @Test
    public void test_a_function_not_reading_arguments_still_runs() {
        assertEquals(5d, number("function f(a,b){ return a + b; } return f(2,3);"));
    }

    @Test
    public void test_arguments_spelled_with_a_unicode_escape_still_resolves() {
        assertEquals(2d, number("function f(){ return \\u0061rguments.length; } return f(8,9);"));
    }

    @Test
    public void test_an_arrow_inherits_the_enclosing_arguments() {
        assertEquals(2d, number("function f(){ const g = () => arguments.length; return g(); } return f(1,2);"));
    }

    @Test
    public void test_a_nested_function_has_its_own_arguments() {
        assertEquals("1|3", string("function outer(){ function inner(){ return arguments.length; }"
                + " return inner(9) + '|' + arguments.length; } return outer(1,2,3);"));
    }

    @Test
    public void test_arguments_tracks_the_parameters_it_was_called_with() {
        assertEquals(0d, number("function f(a){ return arguments.length; } return f();"));
        assertEquals(4d, number("function f(a){ return arguments.length; } return f(1,2,3,4);"));
    }

    @Test
    public void test_a_method_reading_arguments_still_gets_it() {
        assertEquals(2d, number("const o = { m(){ return arguments.length; } }; return o.m('a','b');"));
    }

    @Test
    public void test_a_default_parameter_may_read_arguments() {
        assertEquals(5d, number("function f(a, b = arguments[0] + 1){ return a + b; } return f(2);"));
    }

    @Test
    public void test_a_function_mentioning_arguments_only_in_a_string_still_runs() {
        assertEquals("arguments", string("function f(){ return 'arguments'; } return f();"));
    }

    @Test
    public void test_arguments_is_a_reference_error_only_at_top_level() {
        final var result = simpleJs.run("return arguments.length;", host());
        assertTrue(result.isError(), "top-level arguments must not resolve");
    }
}
