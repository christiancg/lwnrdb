package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.Program;

// A class field initializer has no arguments binding and may not contain a bare super() call, and
// the check has to see through every expression shape the initializer can take.
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

    // A bare reference to arguments in a field initializer is rejected
    @Test
    public void test_bare_arguments() {
        rejected("class C { x = arguments; }");
    }

    // arguments inside a call, in the callee or in an argument, is rejected
    @Test
    public void test_arguments_in_a_call() {
        rejected("class C { x = f(arguments); }");
        rejected("class C { x = arguments(); }");
    }

    // arguments inside a binary or logical expression is rejected
    @Test
    public void test_arguments_in_binary_and_logical_expressions() {
        rejected("class C { x = 1 + arguments; }");
        rejected("class C { x = arguments || 1; }");
    }

    // arguments inside a conditional expression is rejected in every position
    @Test
    public void test_arguments_in_a_conditional() {
        rejected("class C { x = arguments ? 1 : 2; }");
        rejected("class C { x = true ? arguments : 2; }");
        rejected("class C { x = true ? 1 : arguments; }");
    }

    // arguments under a unary operator is rejected
    @Test
    public void test_arguments_in_a_unary_expression() {
        rejected("class C { x = !arguments; }");
    }

    // arguments on the right of an assignment is rejected
    @Test
    public void test_arguments_in_an_assignment() {
        rejected("class C { x = (y = arguments); }");
    }

    // arguments in a sequence expression is rejected
    @Test
    public void test_arguments_in_a_sequence() {
        rejected("class C { x = (1, arguments); }");
    }

    // arguments spread into an array literal is rejected
    @Test
    public void test_arguments_in_an_array_literal() {
        rejected("class C { x = [...arguments]; }");
        rejected("class C { x = [arguments]; }");
    }

    // arguments as an object literal value is rejected
    @Test
    public void test_arguments_in_an_object_literal() {
        rejected("class C { x = { a: arguments }; }");
    }

    // arguments as the object or the computed key of a member expression is rejected
    @Test
    public void test_arguments_in_a_member_expression() {
        rejected("class C { x = arguments.length; }");
        rejected("class C { x = obj[arguments]; }");
    }

    // An arrow function has no arguments binding of its own, so the check reaches into its body
    @Test
    public void test_arguments_inside_an_arrow() {
        rejected("class C { x = () => arguments; }");
        rejected("class C { x = () => { return arguments; }; }");
        rejected("class C { x = () => { arguments; }; }");
        rejected("class C { x = () => { let y = arguments; }; }");
        rejected("class C { x = () => { if (arguments) {} }; }");
    }

    // An ordinary nested function introduces its own arguments binding, so it is allowed
    @Test
    public void test_arguments_inside_a_nested_function_is_allowed() {
        accepted("class C { x = function () { return arguments; }; }");
    }

    // A bare super() call in a field initializer is rejected
    @Test
    public void test_bare_super_call() {
        rejected("class C extends B { x = super(); }");
    }

    // A super() call reached through an arrow body is rejected too
    @Test
    public void test_super_call_inside_an_arrow() {
        rejected("class C extends B { x = () => super(); }");
    }

    // A super property access is not a super call and stays allowed
    @Test
    public void test_super_property_access_is_allowed() {
        accepted("class C extends B { x = super.y; }");
    }

    // A static field initializer is checked the same way
    @Test
    public void test_static_field_initializer() {
        rejected("class C { static x = arguments; }");
    }

    // An initializer without either construct parses
    @Test
    public void test_plain_initializer_parses() {
        accepted("class C { x = 1 + 2; }");
    }
}
