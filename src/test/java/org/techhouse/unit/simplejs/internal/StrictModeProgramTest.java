package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.Program;

// The engine treats all code as a strict module, so these early errors are unconditional.
public class StrictModeProgramTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lexWithPositions(source));
    }

    // delete of an unqualified identifier is a syntax error in strict mode
    @Test
    public void test_delete_bare_identifier_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("delete x"));
    }

    // delete of a member expression is allowed
    @Test
    public void test_delete_member_allowed() {
        assertDoesNotThrow(() -> parse("delete obj.x"));
    }

    // deleting a private field is a syntax error
    @Test
    public void test_delete_private_field_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("class C { #x = 1; m() { delete this.#x; } }"));
    }

    // duplicate parameter names are a syntax error
    @Test
    public void test_duplicate_parameter_names_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("function f(a, a) {}"));
    }

    // duplicate names across a destructuring pattern are a syntax error
    @Test
    public void test_duplicate_parameter_names_in_pattern_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("function f(a, { a }) {}"));
    }

    // distinct parameter names remain valid, including patterns and defaults
    @Test
    public void test_distinct_parameters_allowed() {
        assertDoesNotThrow(() -> parse("function f(a, b = 1, { c }, ...d) {}"));
    }

    // the with statement is a syntax error in strict mode
    @Test
    public void test_with_statement_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("with (o) { x; }"));
    }

    // a future-reserved word may not be a let/const binding
    @Test
    public void test_reserved_word_let_binding_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("let package = 1;"));
    }

    // a future-reserved word may not be a var binding
    @Test
    public void test_reserved_word_var_binding_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("var interface = 1;"));
    }

    // a future-reserved word may not name a function
    @Test
    public void test_reserved_word_function_name_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("function public() {}"));
    }

    // a future-reserved word may not name a class
    @Test
    public void test_reserved_word_class_name_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("class private {}"));
    }

    // a future-reserved word may not be a parameter
    @Test
    public void test_reserved_word_parameter_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("function f(protected) {}"));
    }

    // a future-reserved word may not be a destructured parameter
    @Test
    public void test_reserved_word_destructured_parameter_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("function f({ implements }) {}"));
    }

    // a future-reserved word may not be an arrow parameter
    @Test
    public void test_reserved_word_arrow_parameter_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("const f = interface => interface;"));
    }

    // a future-reserved word is still a valid property key
    @Test
    public void test_reserved_word_as_property_key_allowed() {
        assertDoesNotThrow(() -> parse("const o = { public: 1 }; o.public;"));
    }

    // eval may not be a binding identifier
    @Test
    public void test_eval_binding_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("let eval = 1;"));
    }

    // arguments may not be a binding identifier
    @Test
    public void test_arguments_binding_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("function arguments() {}"));
    }

    // eval may not be an assignment target
    @Test
    public void test_eval_assignment_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("eval = 1;"));
    }

    // arguments may not be an update target
    @Test
    public void test_arguments_update_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("function f() { arguments++; }"));
    }

    // eval may not be a destructuring assignment target
    @Test
    public void test_eval_destructuring_assignment_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("[eval] = [1];"));
    }

    // a plain identifier assignment is still allowed
    @Test
    public void test_plain_assignment_allowed() {
        assertDoesNotThrow(() -> parse("let x = 0; x = 1;"));
    }
}
