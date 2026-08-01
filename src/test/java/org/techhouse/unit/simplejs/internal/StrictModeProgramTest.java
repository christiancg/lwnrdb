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
}
