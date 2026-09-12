package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.Program;

public class FieldInitializerParseTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lexWithPositions(source));
    }

    private static void rejected(String source) {
        assertThrows(SyntaxErrorException.class, () -> parse(source));
    }

    private static void accepted(String source) {
        assertEquals(1, parse(source).getBody().size());
    }

    @Test
    public void test_bare_arguments() {
        rejected("class C { x = arguments; }");
    }

    @Test
    public void test_arguments_in_a_call() {
        rejected("class C { x = f(arguments); }");
        rejected("class C { x = arguments(); }");
    }

    @Test
    public void test_arguments_in_binary_and_logical_expressions() {
        rejected("class C { x = 1 + arguments; }");
        rejected("class C { x = arguments || 1; }");
    }

    @Test
    public void test_arguments_in_a_conditional() {
        rejected("class C { x = arguments ? 1 : 2; }");
        rejected("class C { x = true ? arguments : 2; }");
        rejected("class C { x = true ? 1 : arguments; }");
    }

    @Test
    public void test_arguments_in_a_unary_expression() {
        rejected("class C { x = !arguments; }");
    }

    @Test
    public void test_arguments_in_an_assignment() {
        rejected("class C { x = (y = arguments); }");
    }

    @Test
    public void test_arguments_in_a_sequence() {
        rejected("class C { x = (1, arguments); }");
    }

    @Test
    public void test_arguments_in_an_array_literal() {
        rejected("class C { x = [...arguments]; }");
        rejected("class C { x = [arguments]; }");
    }

    @Test
    public void test_arguments_in_an_object_literal() {
        rejected("class C { x = { a: arguments }; }");
    }

    @Test
    public void test_arguments_in_a_member_expression() {
        rejected("class C { x = arguments.length; }");
        rejected("class C { x = obj[arguments]; }");
    }

    @Test
    public void test_arguments_inside_an_arrow() {
        rejected("class C { x = () => arguments; }");
        rejected("class C { x = () => { return arguments; }; }");
        rejected("class C { x = () => { arguments; }; }");
        rejected("class C { x = () => { let y = arguments; }; }");
        rejected("class C { x = () => { if (arguments) {} }; }");
    }

    @Test
    public void test_arguments_inside_a_nested_function_is_allowed() {
        accepted("class C { x = function () { return arguments; }; }");
    }

    @Test
    public void test_bare_super_call() {
        rejected("class C extends B { x = super(); }");
    }

    @Test
    public void test_super_call_inside_an_arrow() {
        rejected("class C extends B { x = () => super(); }");
    }

    @Test
    public void test_super_property_access_is_allowed() {
        accepted("class C extends B { x = super.y; }");
    }

    @Test
    public void test_static_field_initializer() {
        rejected("class C { static x = arguments; }");
    }

    @Test
    public void test_plain_initializer_parses() {
        accepted("class C { x = 1 + 2; }");
    }
}
