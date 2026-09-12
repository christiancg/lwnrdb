package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.DoWhileStatement;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;

public class ParserLoopSyntaxTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    private static Statement firstStatement(String source) {
        return parse(source).getBody().getFirst();
    }

    @Test
    public void test_using_in_for_of_head() {
        final var statement = assertInstanceOf(ForOfStatement.class, firstStatement("for (using r of xs) {}"));
        final var declaration = assertInstanceOf(VariableDeclaration.class, statement.getLeft());
        assertEquals("using", declaration.getKind());
        assertNull(declaration.getDeclarations().getFirst().getInit());
    }

    @Test
    public void test_using_as_for_of_variable() {
        final var statement = assertInstanceOf(ForOfStatement.class, firstStatement("for (using of xs) {}"));
        assertInstanceOf(Identifier.class, statement.getLeft());
    }

    @Test
    public void test_using_rejected_in_for_in_head() {
        assertThrows(SyntaxErrorException.class, () -> parse("for (using k in obj) {}"));
        assertThrows(SyntaxErrorException.class, () -> parse("for (using i; i < 1; i++) {}"));
    }

    @Test
    public void test_while_statement() {
        final var loop = assertInstanceOf(WhileStatement.class, firstStatement("while (x < 10) x++;"));
        assertInstanceOf(BinaryExpression.class, loop.getTest());
    }

    @Test
    public void test_do_while_statement() {
        final var loop = assertInstanceOf(DoWhileStatement.class, firstStatement("do x++; while (x < 10);"));
        assertInstanceOf(ExpressionStatement.class, loop.getBody());
        assertInstanceOf(BinaryExpression.class, loop.getTest());
    }

    @Test
    public void test_do_while_missing_while_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("do x; y;"));
    }

    @Test
    public void test_for_statement() {
        final var loop = assertInstanceOf(ForStatement.class, firstStatement("for (let i = 0; i < 3; i++) x;"));
        assertInstanceOf(VariableDeclaration.class, loop.getInit());
        assertInstanceOf(BinaryExpression.class, loop.getTest());
        assertInstanceOf(UpdateExpression.class, loop.getUpdate());
    }

    @Test
    public void test_for_statement_empty_clauses() {
        final var loop = assertInstanceOf(ForStatement.class, firstStatement("for (;;) {}"));
        assertNull(loop.getInit());
        assertNull(loop.getTest());
        assertNull(loop.getUpdate());
    }

    @Test
    public void test_for_statement_expression_init() {
        final var loop = assertInstanceOf(ForStatement.class, firstStatement("for (i = 0; i < 3; i++) x;"));
        assertInstanceOf(AssignmentExpression.class, loop.getInit());
    }

    @Test
    public void test_for_of_statement() {
        final var loop = assertInstanceOf(ForOfStatement.class, firstStatement("for (const x of arr) {}"));
        assertInstanceOf(VariableDeclaration.class, loop.getLeft());
        assertInstanceOf(Identifier.class, loop.getRight());
        assertInstanceOf(BlockStatement.class, loop.getBody());
        assertFalse(loop.isAwait());
    }

    @Test
    public void test_for_await_of_statement() {
        final var loop = assertInstanceOf(ForOfStatement.class, firstStatement("for await (const x of gen) {}"));
        assertTrue(loop.isAwait());
        assertInstanceOf(VariableDeclaration.class, loop.getLeft());
    }

    @Test
    public void test_for_await_requires_of() {
        assertThrows(UnexpectedTokenException.class, () -> parse("for await (const x in obj) {}"));
        assertThrows(UnexpectedTokenException.class, () -> parse("for await (;;) {}"));
    }

    @Test
    public void test_for_in_statement() {
        final var loop = assertInstanceOf(ForInStatement.class, firstStatement("for (let k in obj) {}"));
        assertInstanceOf(VariableDeclaration.class, loop.getLeft());
        assertInstanceOf(Identifier.class, loop.getRight());
    }

    @Test
    public void test_for_in_expression_target() {
        final var loop = assertInstanceOf(ForInStatement.class, firstStatement("for (a in b);"));
        assertInstanceOf(Identifier.class, loop.getLeft());
    }

    @Test
    public void test_for_in_member_target() {
        final var loop = assertInstanceOf(ForInStatement.class, firstStatement("for (o.p in b);"));
        assertInstanceOf(MemberExpression.class, loop.getLeft());
    }

    @Test
    public void test_for_no_in_inside_parens() {
        final var loop = assertInstanceOf(ForStatement.class, firstStatement("for (i = (a in b); ; );"));
        assertInstanceOf(AssignmentExpression.class, loop.getInit());
    }

    @Test
    public void test_for_in_with_initializer_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("for (let x = 1 in y);"));
    }

    @Test
    public void test_for_in_non_assignable_target_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("for (1 in y);"));
    }

    @Test
    public void test_for_of_multiple_declarators_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("for (let a, b of y);"));
    }

    @Test
    public void test_get_before_async_member_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class C { get async foo() {} }"));
    }

    @Test
    public void test_for_of_pattern_declaration() {
        final var forOf = assertInstanceOf(ForOfStatement.class, firstStatement("for (const [a, b] of x) {}"));
        final var declaration = assertInstanceOf(VariableDeclaration.class, forOf.getLeft());
        assertInstanceOf(ArrayPattern.class, declaration.getDeclarations().getFirst().getId());
    }

    @Test
    public void test_for_of_pattern_assignment() {
        final var forOf = assertInstanceOf(ForOfStatement.class, firstStatement("for ([a] of x) {}"));
        assertInstanceOf(ArrayPattern.class, forOf.getLeft());
    }

    @Test
    public void test_rest_before_end_array_pattern_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("const [...r, a] = x"));
    }
}
