package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.WhileStatement;

// ASI is only exercised through the position-aware parse path (lexWithPositions carries the
// newlineBefore signal); the token-list overload degrades to permissive.
public class AsiProgramTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lexWithPositions(source));
    }

    @Test
    public void test_asi_inserts_between_declarations_on_new_lines() {
        assertEquals(2, parse("let a = 1\nlet b = 2").getBody().size());
    }

    @Test
    public void test_asi_before_eof() {
        assertEquals(1, parse("let a = 1").getBody().size());
    }

    @Test
    public void test_asi_before_closing_brace() {
        final var block = assertInstanceOf(BlockStatement.class, parse("{ let a = 1 }").getBody().getFirst());
        assertEquals(1, block.getBody().size());
    }

    @Test
    public void test_return_newline_drops_argument() {
        final var body = parse("function f() { return\n5 }").getBody();
        final var fnBody = assertInstanceOf(FunctionDeclaration.class, body.getFirst()).getBody();
        final var ret = assertInstanceOf(ReturnStatement.class, fnBody.getBody().getFirst());
        assertNull(ret.getArgument());
        assertEquals(2, fnBody.getBody().size());
    }

    @Test
    public void test_postfix_increment_newline_is_prefix_next_statement() {
        final var body = parse("a\n++\nb").getBody();
        assertEquals(2, body.size());
        final var second = assertInstanceOf(ExpressionStatement.class, body.get(1));
        final var update = assertInstanceOf(UpdateExpression.class, second.getExpression());
        assertTrue(update.isPrefix());
    }

    @Test
    public void test_break_newline_drops_label() {
        final var loop = assertInstanceOf(WhileStatement.class,
                parse("while (true) { break\nx }").getBody().getFirst());
        final var block = assertInstanceOf(BlockStatement.class, loop.getBody());
        final var brk = assertInstanceOf(BreakStatement.class, block.getBody().getFirst());
        assertNull(brk.getLabel());
        assertEquals(2, block.getBody().size());
    }

    @Test
    public void test_throw_newline_is_syntax_error() {
        assertThrows(UnexpectedTokenException.class, () -> parse("throw\nx"));
    }

    @Test
    public void test_arrow_newline_before_fat_arrow_is_syntax_error() {
        assertThrows(UnexpectedTokenException.class, () -> parse("const f = (a)\n=> a"));
    }

    @Test
    public void test_single_identifier_arrow_newline_is_syntax_error() {
        assertThrows(UnexpectedTokenException.class, () -> parse("const f = a\n=> a"));
    }

    @Test
    public void test_same_line_statements_without_separator_rejected() {
        assertThrows(UnexpectedTokenException.class, () -> parse("a = 1 b = 2"));
    }

    @Test
    public void test_do_while_semicolon_always_inserted_after_close_paren() {
        final var body = parse("do break; while (0) x = 42;").getBody();
        assertEquals(2, body.size());
        assertInstanceOf(DoWhileStatement.class, body.getFirst());
        assertInstanceOf(ExpressionStatement.class, body.get(1));
    }

    @Test
    public void test_do_while_semicolon_inserted_without_explicit_semicolon() {
        final var body = parse("do ; while (x) x = 39;").getBody();
        assertEquals(2, body.size());
        assertInstanceOf(DoWhileStatement.class, body.getFirst());
        assertInstanceOf(ExpressionStatement.class, body.get(1));
    }
}
