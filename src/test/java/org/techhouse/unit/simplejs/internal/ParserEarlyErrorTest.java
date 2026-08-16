package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.exceptions.UnterminatedRegexException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.RegexLiteral;

// The early errors the front end raises before a single statement runs. A test262
// `negative: phase: parse` test calls $DONOTEVALUATE(), so an engine that only fails at runtime is
// indistinguishable from one that never fails at all: these have to be rejected while parsing.
public class ParserEarlyErrorTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lexWithPositions(source));
    }

    // AllPrivateNamesValid: a private name with no declaration in an enclosing class is rejected
    @Test
    public void test_undeclared_private_name_is_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("this.#x"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { this.#x } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { #x in this } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { #x; } class D { m() { C.#x } }"));
    }

    // The check reaches through a nested arrow, past a nested class's own private environment, and
    // into the heritage, which is evaluated outside the class it belongs to
    @Test
    public void test_undeclared_private_name_through_nested_scopes() {
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { () => this.#x } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { class Inner { #y; } this.#y } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C extends (this.#x) { #x; }"));
    }

    // Every name a class body declares is in scope for the whole body, nested classes included
    @Test
    public void test_declared_private_name_parses() {
        assertEquals(1, parse("class C { m() { return this.#x } #x = 1; }").getBody().size());
        assertEquals(1, parse("class C { #x; m() { class Inner { n() { return this.#x } } } }").getBody().size());
    }

    // An invalid regular expression literal is rejected while parsing, not on first evaluation
    @Test
    public void test_invalid_regexp_literal_is_parse_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("/(/;"));
        assertThrows(SyntaxErrorException.class, () -> parse("/a/gg;"));
        assertThrows(SyntaxErrorException.class, () -> parse("/\\p{Emoji}/u;"));
        assertThrows(UnterminatedRegexException.class, () -> parse("/a"));
    }

    // A malformed numeric literal is a Syntax Error, not a number followed by a stray token
    @Test
    public void test_invalid_numeric_literal_is_parse_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("0x;"));
        assertThrows(SyntaxErrorException.class, () -> parse("0b2;"));
        assertThrows(SyntaxErrorException.class, () -> parse("0_0;"));
        assertThrows(SyntaxErrorException.class, () -> parse("3in [];"));
    }

    // break and continue need a label that is in scope, and continue's must label an iteration
    // statement; neither reaches across a function boundary
    @Test
    public void test_illegal_break_label_is_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("break;"));
        assertThrows(SyntaxErrorException.class, () -> parse("continue;"));
        assertThrows(SyntaxErrorException.class, () -> parse("outer: while (false) { break inner; }"));
        assertThrows(SyntaxErrorException.class, () -> parse("outer: { continue outer; }"));
        assertThrows(SyntaxErrorException.class,
                () -> parse("outer: while (false) { (function () { break outer; }) }"));
        assertEquals(1, parse("outer: while (false) { break outer; }").getBody().size());
        assertEquals(1, parse("while (false) { switch (0) { case 0: break; } }").getBody().size());
    }

    // After the closing brace of a block or a declaration body a slash begins a regular expression;
    // after a function expression's body it is still division
    @Test
    public void test_slash_after_block_close_is_regex_not_division() {
        final var block = parse("{}/1/;").getBody();
        assertEquals(2, block.size());
        assertInstanceOf(RegexLiteral.class, ((ExpressionStatement) block.get(1)).getExpression());
        final var declaration = parse("function fn() {}/1/g;").getBody();
        assertEquals(2, declaration.size());
        assertInstanceOf(RegexLiteral.class, ((ExpressionStatement) declaration.get(1)).getExpression());
        assertInstanceOf(BinaryExpression.class,
                ((ExpressionStatement) parse("(function () {} / 1);").getBody().getFirst()).getExpression());
    }

    // U+2028, U+2029 and a bare carriage return are line terminators: they close a single-line
    // comment and they drive automatic semicolon insertion
    @Test
    public void test_line_separator_terminates_statement() {
        final var lineSeparator = String.valueOf((char) 0x2028);
        final var paragraphSeparator = String.valueOf((char) 0x2029);
        assertEquals(1, parse("// comment" + lineSeparator + "var x = 1;").getBody().size());
        assertEquals(2, parse("var x = 1" + paragraphSeparator + "var y = 2").getBody().size());
        assertEquals(1, parse("// comment\rvar x = 1;").getBody().size());
        assertThrows(UnexpectedTokenException.class, () -> parse("// comment" + lineSeparator + "?"));
    }
}
