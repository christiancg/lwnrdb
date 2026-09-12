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

// A test262 `negative: phase: parse` test calls $DONOTEVALUATE(), so an engine that only fails at runtime
// is indistinguishable from one that never fails at all: these have to be rejected while parsing.
public class ParserEarlyErrorTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lexWithPositions(source));
    }

    @Test
    public void test_undeclared_private_name_is_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("this.#x"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { this.#x } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { #x in this } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { #x; } class D { m() { C.#x } }"));
    }

    @Test
    public void test_undeclared_private_name_through_nested_scopes() {
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { () => this.#x } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { m() { class Inner { #y; } this.#y } }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C extends (this.#x) { #x; }"));
    }

    // RelationalExpression : PrivateIdentifier in ShiftExpression - the right operand is restricted to
    // ShiftExpression grade, so both shapes have to fail at parse time rather than at runtime.
    @Test
    public void test_private_in_restricts_right_operand_to_shift_expression() {
        assertThrows(SyntaxErrorException.class,
                () -> parse("class C { #field; constructor() { #field in #field in this; } }"));
        assertThrows(SyntaxErrorException.class,
                () -> parse("class C { #field; constructor() { #field in () => {}; } }"));
        assertEquals(Program.class,
                parse("class C { #field; constructor() { #field in (#field in this); } }").getClass());
        assertEquals(Program.class, parse("class C { #field; constructor() { #field in (() => {}); } }").getClass());
    }

    @Test
    public void test_declared_private_name_parses() {
        assertEquals(1, parse("class C { m() { return this.#x } #x = 1; }").getBody().size());
        assertEquals(1, parse("class C { #x; m() { class Inner { n() { return this.#x } } } }").getBody().size());
    }

    @Test
    public void test_invalid_regexp_literal_is_parse_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("/(/;"));
        assertThrows(SyntaxErrorException.class, () -> parse("/a/gg;"));
        assertThrows(SyntaxErrorException.class, () -> parse("/\\p{Emoji}/u;"));
        assertThrows(UnterminatedRegexException.class, () -> parse("/a"));
    }

    @Test
    public void test_invalid_numeric_literal_is_parse_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("0x;"));
        assertThrows(SyntaxErrorException.class, () -> parse("0b2;"));
        assertThrows(SyntaxErrorException.class, () -> parse("0_0;"));
        assertThrows(SyntaxErrorException.class, () -> parse("3in [];"));
    }

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

    // After a function expression's body a slash is still division, unlike after a block or a declaration body.
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

    @Test
    public void test_line_separator_terminates_statement() {
        final var lineSeparator = String.valueOf((char) 0x2028);
        final var paragraphSeparator = String.valueOf((char) 0x2029);
        assertEquals(1, parse("// comment" + lineSeparator + "var x = 1;").getBody().size());
        assertEquals(2, parse("var x = 1" + paragraphSeparator + "var y = 2").getBody().size());
        assertEquals(1, parse("// comment\rvar x = 1;").getBody().size());
        assertThrows(UnexpectedTokenException.class, () -> parse("// comment" + lineSeparator + "?"));
    }

    // ArrowFunction : ArrowParameters => ConciseBody is a Syntax Error if ArrowParameters Contains a
    // YieldExpression or an AwaitExpression: the cover-grammar parse inherits the enclosing [Yield]/[Await].
    @Test
    public void test_arrow_parameters_containing_yield_or_await_is_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("function *g() { (x = yield) => {}; }"));
        assertThrows(SyntaxErrorException.class, () -> parse("async() => { (a = await 1) => {}; };"));
        assertEquals(1, parse("function *g() { (x = 1) => x; }").getBody().size());
    }

    @Test
    public void test_escaped_async_is_an_ordinary_identifier() {
        assertEquals(1, parse("\\u0061sync;").getBody().size());
        assertEquals(1, parse("for (\\u0061sync of [7]);").getBody().size());
    }

    // ConditionalExpression[In]'s consequent is AssignmentExpression[+In]: it allows `in` even inside a
    // classic for-loop header's noIn production, unlike the alternate branch.
    @Test
    public void test_conditional_consequent_allows_in_inside_for_header() {
        assertEquals(1, parse("for (true ? '' in {} : 0; false; ) ;").getBody().size());
    }

    @Test
    public void test_async_function_declaration_rejects_line_terminator() {
        assertEquals(2, parse("async\nfunction foo() {}").getBody().size());
        assertEquals(1, parse("async function foo() {}").getBody().size());
    }

    @Test
    public void test_contextual_keywords_are_legal_labels() {
        assertEquals(1, parse("await: 1;").getBody().size());
        assertEquals(1, parse("async: 1;").getBody().size());
        assertEquals(1, parse("of: 1;").getBody().size());
    }

    @Test
    public void test_using_declaration_may_bind_the_name_of() {
        assertEquals(1, parse("for (using of = null;;) break;").getBody().size());
        assertEquals(1, parse("{ using of = null; }").getBody().size());
    }

    @Test
    public void test_destructuring_shorthand_accepts_contextual_keyword_name() {
        assertEquals(1, parse("(() => { var {await} = {}; });").getBody().size());
        assertEquals(1, parse("var {of} = {};").getBody().size());
    }
}
