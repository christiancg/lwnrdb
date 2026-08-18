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

    // ArrowFunction : ArrowParameters => ConciseBody - a Syntax Error if ArrowParameters Contains a
    // YieldExpression or an AwaitExpression: the cover-grammar parse of the parenthesized head still
    // inherits the enclosing [Yield]/[Await] parameter even though the arrow itself resets both for
    // its own body, so `yield`/`await` in a default parameter value is rejected here rather than
    // silently falling back to an identifier reference.
    @Test
    public void test_arrow_parameters_containing_yield_or_await_is_syntax_error() {
        assertThrows(SyntaxErrorException.class, () -> parse("function *g() { (x = yield) => {}; }"));
        assertThrows(SyntaxErrorException.class, () -> parse("async() => { (a = await 1) => {}; };"));
        // a default value with no yield/await containment still parses normally
        assertEquals(1, parse("function *g() { (x = 1) => x; }").getBody().size());
    }

    // An escaped contextual keyword (`async`, here) is never treated as the keyword: it is only ever
    // an ordinary identifier reference, so it neither triggers the async-arrow/async-function-call
    // production nor is rejected as a reserved word.
    @Test
    public void test_escaped_async_is_an_ordinary_identifier() {
        assertEquals(1, parse("\\u0061sync;").getBody().size());
        assertEquals(1, parse("for (\\u0061sync of [7]);").getBody().size());
    }

    // ConditionalExpression[In] : LogicalORExpression[?In] ? AssignmentExpression[+In] :
    // AssignmentExpression[?In] - the consequent branch always allows `in`, even inside the noIn
    // production of a classic for-loop header, unlike the alternate branch.
    @Test
    public void test_conditional_consequent_allows_in_inside_for_header() {
        assertEquals(1, parse("for (true ? '' in {} : 0; false; ) ;").getBody().size());
    }

    // `async [no LineTerminator here] function` at statement position: a line break between the two
    // demotes `async` to a plain identifier reference followed by a separate function declaration,
    // exactly as it already does at expression position.
    @Test
    public void test_async_function_declaration_rejects_line_terminator() {
        assertEquals(2, parse("async\nfunction foo() {}").getBody().size());
        assertEquals(1, parse("async function foo() {}").getBody().size());
    }

    // A LabelIdentifier is exactly as permissive as an IdentifierReference: `await`/`async`/`of`,
    // unescaped, lex as KEYWORD tokens rather than IDENTIFIER, but remain legal labels in non-module,
    // non-async code, wherever they are legal identifiers.
    @Test
    public void test_contextual_keywords_are_legal_labels() {
        assertEquals(1, parse("await: 1;").getBody().size());
        assertEquals(1, parse("async: 1;").getBody().size());
        assertEquals(1, parse("of: 1;").getBody().size());
    }

    // The `using of` lookahead restriction that disambiguates a for-of head only applies there; a
    // classic for-loop (and a plain statement) may declare a `using` binding literally named `of`.
    @Test
    public void test_using_declaration_may_bind_the_name_of() {
        assertEquals(1, parse("for (using of = null;;) break;").getBody().size());
        assertEquals(1, parse("{ using of = null; }").getBody().size());
    }

    // A shorthand destructuring-pattern property's key doubles as a binding IdentifierReference, so a
    // contextual keyword such as `await` (outside a reserved context) is as eligible as a genuine
    // identifier token there, the same way it already is for an object *expression*'s shorthand.
    @Test
    public void test_destructuring_shorthand_accepts_contextual_keyword_name() {
        assertEquals(1, parse("(() => { var {await} = {}; });").getBody().size());
        assertEquals(1, parse("var {of} = {};").getBody().size());
    }
}
