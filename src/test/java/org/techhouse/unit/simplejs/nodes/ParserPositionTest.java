package org.techhouse.unit.simplejs.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.Program;

public class ParserPositionTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lexWithPositions(source));
    }

    @Test
    public void test_stamps_every_statement_with_its_own_line() {
        final var program = parse("""
                const a = 1;
                const b = 2;
                a + b;
                """);
        final var body = program.getBody();
        assertEquals(3, body.size());
        for (var i = 0; i < body.size(); i++) {
            assertNotNull(body.get(i).getPosition());
            assertEquals(i + 1, body.get(i).getPosition().getLine());
        }
    }

    @Test
    public void test_stamps_a_call_expression_with_its_own_position() {
        final var program = parse("""
                f(
                  1
                );
                """);
        final var statement = (ExpressionStatement) program.getBody().getFirst();
        final var call = (CallExpression) statement.getExpression();
        assertNotNull(call.getPosition());
        assertEquals(1, call.getPosition().getLine());
    }

    @Test
    public void test_stamps_a_new_expression() {
        final var program = parse("""
                const x = 1;
                new Thing();
                """);
        final var statement = (ExpressionStatement) program.getBody().get(1);
        final var expression = (NewExpression) statement.getExpression();
        assertNotNull(expression.getPosition());
        assertEquals(2, expression.getPosition().getLine());
    }

    @Test
    public void test_nested_statements_are_stamped() {
        final var program = parse("""
                function f() {
                  throw new Error('x');
                }
                """);
        assertEquals(1, program.getBody().getFirst().getPosition().getLine());
    }

    // The token-list entry point carries no positions, so nodes degrade to a name with no location
    @Test
    public void test_token_list_parse_leaves_positions_null() {
        final var program = Parser.parse(Lexer.lex("const a = 1;"));
        assertNull(program.getBody().getFirst().getPosition());
    }
}
