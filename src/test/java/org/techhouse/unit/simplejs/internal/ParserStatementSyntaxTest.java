package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.SyntaxErrorException;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.EmptyStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.LabeledStatement;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.ThisExpression;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.VariableDeclarator;
import org.techhouse.simplejs.nodes.WhileStatement;

public class ParserStatementSyntaxTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    private static Statement firstStatement(String source) {
        return parse(source).getBody().getFirst();
    }

    private static Expression firstExpression(String source) {
        return ((ExpressionStatement) firstStatement(source)).getExpression();
    }

    @Test
    public void test_empty_statement() {
        assertInstanceOf(EmptyStatement.class, firstStatement(";"));
    }

    @Test
    public void test_identifier_and_this() {
        assertEquals("foo", assertInstanceOf(Identifier.class, firstExpression("foo")).getName());
        assertInstanceOf(ThisExpression.class, firstExpression("this"));
    }

    @Test
    public void test_typeof_void_delete() {
        assertEquals("typeof", assertInstanceOf(UnaryExpression.class, firstExpression("typeof x")).getOperator());
        assertEquals("void", assertInstanceOf(UnaryExpression.class, firstExpression("void 0")).getOperator());
        assertEquals("delete", assertInstanceOf(UnaryExpression.class, firstExpression("delete a.b")).getOperator());
    }

    // PropertyDefinition : CoverInitializedName is always a Syntax Error (12.2.6 Early Errors) unless the
    // object literal is reinterpreted as an ObjectAssignmentPattern by an immediately following `=`.
    @Test
    public void test_object_cover_initialized_shorthand_as_bare_expression_throws() {
        assertThrows(SyntaxErrorException.class, () -> firstExpression("({ a = 1 })"));
    }

    @Test
    public void test_arrow_paren_params_and_block_body() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("(a, b) => { return a; }"));
        assertEquals(2, arrow.getParams().size());
        assertFalse(arrow.isExpressionBody());
        assertInstanceOf(BlockStatement.class, arrow.getBody());
    }

    @Test
    public void test_variable_declaration_kinds_and_multiple_declarators() {
        assertEquals("var", assertInstanceOf(VariableDeclaration.class, firstStatement("var x = 1;")).getKind());
        assertEquals("let", assertInstanceOf(VariableDeclaration.class, firstStatement("let y;")).getKind());
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("const a = 1, b = 2;"));
        assertEquals("const", decl.getKind());
        assertEquals(2, decl.getDeclarations().size());
        assertNull(assertInstanceOf(VariableDeclaration.class, firstStatement("let z;")).getDeclarations().getFirst()
                .getInit());
    }

    @Test
    public void test_using_declaration() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("using r = getResource();"));
        assertEquals("using", decl.getKind());
        assertEquals("r", ((Identifier) decl.getDeclarations().getFirst().getId()).getName());
        assertNotNull(decl.getDeclarations().getFirst().getInit());
    }

    @Test
    public void test_await_using_declaration() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("await using r = f();"));
        assertEquals("await using", decl.getKind());
    }

    @Test
    public void test_using_missing_initializer_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("using r;"));
    }

    @Test
    public void test_using_as_identifier() {
        assertInstanceOf(ExpressionStatement.class, firstStatement("using;"));
        assertInstanceOf(ExpressionStatement.class, firstStatement("using = 1;"));
        assertInstanceOf(ExpressionStatement.class, firstStatement("using[a] = arr;"));
    }

    @Test
    public void test_using_rejected_in_switch_clause() {
        assertThrows(SyntaxErrorException.class, () -> parse("switch (x) { case 0: using r = a; }"));
        assertThrows(SyntaxErrorException.class, () -> parse("switch (x) { default: using r = a; }"));
    }

    @Test
    public void test_block_statement() {
        final var block = assertInstanceOf(BlockStatement.class, firstStatement("{ let x = 1; x; }"));
        assertEquals(2, block.getBody().size());
    }

    @Test
    public void test_if_else() {
        final var iff = assertInstanceOf(IfStatement.class, firstStatement("if (x) y; else z;"));
        assertInstanceOf(ExpressionStatement.class, iff.getConsequent());
        assertInstanceOf(ExpressionStatement.class, iff.getAlternate());
        assertNull(assertInstanceOf(IfStatement.class, firstStatement("if (x) y;")).getAlternate());
    }

    @Test
    public void test_try_catch() {
        final var stmt = assertInstanceOf(TryStatement.class, firstStatement("try { x; } catch (e) { y; }"));
        assertInstanceOf(BlockStatement.class, stmt.getBlock());
        assertEquals("e", assertInstanceOf(Identifier.class, stmt.getHandler().getParam()).getName());
        assertNull(stmt.getFinalizer());
    }

    @Test
    public void test_try_catch_finally() {
        final var stmt = assertInstanceOf(TryStatement.class,
                firstStatement("try { x; } catch (e) { y; } finally { z; }"));
        assertNotNull(stmt.getHandler());
        assertInstanceOf(BlockStatement.class, stmt.getFinalizer());
    }

    @Test
    public void test_try_finally_without_catch() {
        final var stmt = assertInstanceOf(TryStatement.class, firstStatement("try { x; } finally { z; }"));
        assertNull(stmt.getHandler());
        assertInstanceOf(BlockStatement.class, stmt.getFinalizer());
    }

    @Test
    public void test_throw_statement() {
        final var stmt = assertInstanceOf(ThrowStatement.class, firstStatement("throw new Error(\"boom\");"));
        assertInstanceOf(NewExpression.class, stmt.getArgument());
    }

    @Test
    public void test_switch_with_default() {
        final var stmt = assertInstanceOf(SwitchStatement.class,
                firstStatement("switch (x) { case 1: a; b; case 2: break; default: c; }"));
        assertInstanceOf(Identifier.class, stmt.getDiscriminant());
        assertEquals(3, stmt.getCases().size());
        assertEquals(2, stmt.getCases().get(0).getConsequent().size());
        assertNull(stmt.getCases().get(2).getTest());
    }

    @Test
    public void test_switch_empty_body() {
        final var stmt = assertInstanceOf(SwitchStatement.class, firstStatement("switch (x) {}"));
        assertTrue(stmt.getCases().isEmpty());
    }

    @Test
    public void test_return_with_and_without_argument() {
        final var body = assertInstanceOf(FunctionDeclaration.class, firstStatement("function f() { return 1; }"))
                .getBody();
        assertInstanceOf(NumberLiteral.class,
                assertInstanceOf(ReturnStatement.class, body.getBody().getFirst()).getArgument());
        final var empty = assertInstanceOf(FunctionDeclaration.class, firstStatement("function g() { return; }"))
                .getBody();
        assertNull(assertInstanceOf(ReturnStatement.class, empty.getBody().getFirst()).getArgument());
    }

    @Test
    public void test_break_and_continue() {
        final var loop = assertInstanceOf(WhileStatement.class, firstStatement("while (x) { break; continue; }"));
        final var block = assertInstanceOf(BlockStatement.class, loop.getBody());
        final var brk = assertInstanceOf(BreakStatement.class, block.getBody().get(0));
        final var cont = assertInstanceOf(ContinueStatement.class, block.getBody().get(1));
        assertNull(brk.getLabel());
        assertNull(cont.getLabel());
    }

    @Test
    public void test_labeled_statement_with_labeled_break_continue() {
        final var labeled = assertInstanceOf(LabeledStatement.class,
                firstStatement("outer: while (x) { break outer; continue outer; }"));
        assertEquals("outer", labeled.getLabel().getName());
        final var loop = assertInstanceOf(WhileStatement.class, labeled.getBody());
        final var block = assertInstanceOf(BlockStatement.class, loop.getBody());
        assertEquals("outer", ((BreakStatement) block.getBody().get(0)).getLabel().getName());
        assertEquals("outer", ((ContinueStatement) block.getBody().get(1)).getLabel().getName());
    }

    @Test
    public void test_function_declaration() {
        final var fn = assertInstanceOf(FunctionDeclaration.class,
                firstStatement("function add(a, b) { return a + b; }"));
        assertEquals("add", fn.getName().getName());
        assertEquals(2, fn.getParams().size());
    }

    @Test
    public void test_incomplete_binary_throws_end_of_input() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("1 +"));
    }

    @Test
    public void test_missing_closing_paren_throws_end_of_input() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("(1"));
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("if (x"));
    }

    @Test
    public void test_assignment_to_literal_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("1 = 2"));
    }

    @Test
    public void test_unexpected_separator_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("({ a: })"));
        assertThrows(UnexpectedTokenException.class, () -> parse("function () {}"));
    }

    @Test
    public void test_unexpected_keyword_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("1 + case"));
    }

    @Test
    public void test_unexpected_identifier_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("({ [k] v })"));
    }

    @Test
    public void test_variable_declaration_bad_targets_throw() {
        assertThrows(UnexpectedTokenException.class, () -> parse("var 1"));
        assertThrows(UnexpectedTokenException.class, () -> parse("var \"s\""));
        assertThrows(UnexpectedTokenException.class, () -> parse("var true"));
        assertThrows(UnexpectedTokenException.class, () -> parse("var null"));
        assertThrows(UnexpectedTokenException.class, () -> parse("var /a/g"));
        assertThrows(UnexpectedTokenException.class, () -> parse("var `t`"));
    }

    // `var undefined;` is legal but `let`/`const undefined` is a SyntaxError in real engines; this engine
    // reaches the same outcome by never accepting "undefined" as a lexical BindingIdentifier.
    @Test
    public void test_var_undefined_parses_but_lexical_undefined_throws() {
        assertDoesNotThrow(() -> parse("var undefined;"));
        assertThrows(UnexpectedTokenException.class, () -> parse("let undefined;"));
        assertThrows(UnexpectedTokenException.class, () -> parse("const undefined = 1;"));
    }

    @Test
    public void test_bad_template_expression_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("`a${1 2}b`"));
    }

    @Test
    public void test_try_without_catch_or_finally_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("try { x; } y;"));
    }

    @Test
    public void test_switch_missing_colon_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("switch (x) { case 1 a; }"));
    }

    @Test
    public void test_unterminated_switch_throws_end_of_input() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("switch (x) { case 1:"));
    }

    @Test
    public void test_async_function_declaration() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("async function f() {}"));
        assertTrue(fn.isAsync());
        assertFalse(fn.isGenerator());
    }

    @Test
    public void test_generator_function_declaration() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* f() {}"));
        assertTrue(fn.isGenerator());
        assertFalse(fn.isAsync());
    }

    @Test
    public void test_async_arrow_block_body() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("async () => {}"));
        assertTrue(arrow.isAsync());
        assertFalse(arrow.isExpressionBody());
    }

    @Test
    public void test_invalid_constructor_member_is_rejected() {
        assertThrows(SyntaxErrorException.class, () -> parse("class C { async constructor() {} }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { * constructor() {} }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { get constructor() {} }"));
        assertThrows(SyntaxErrorException.class, () -> parse("class C { constructor() {} constructor() {} }"));
        final var decl = assertInstanceOf(ClassDeclaration.class,
                firstStatement("class C { static async constructor() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("method", method.getKind());
    }

    @Test
    public void test_async_without_function_or_arrow_is_an_identifier() {
        assertEquals("async", assertInstanceOf(Identifier.class, firstExpression("async")).getName());
        assertEquals("async", assertInstanceOf(Identifier.class, firstExpression("async;")).getName());
        assertThrows(RuntimeException.class, () -> parse("async +"));
    }

    @Test
    public void test_yield_delegate_without_argument_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("function* g() { yield*; }"));
    }

    @Test
    public void test_async_getter_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class C { async get x() {} }"));
    }

    @Test
    public void test_param_after_rest_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("function f(...rest, a) {}"));
    }

    @Test
    public void test_spread_without_argument_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("f(...)"));
    }

    private static VariableDeclarator firstDeclarator(String source) {
        return ((VariableDeclaration) firstStatement(source)).getDeclarations().getFirst();
    }

    @Test
    public void test_array_pattern_declaration() {
        final var pattern = assertInstanceOf(ArrayPattern.class, firstDeclarator("const [a, b] = arr").getId());
        assertEquals(2, pattern.getElements().size());
        assertEquals("a", assertInstanceOf(Identifier.class, pattern.getElements().getFirst()).getName());
    }

    @Test
    public void test_object_pattern_declaration() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a, b} = o").getId());
        assertEquals(2, pattern.getProperties().size());
        assertTrue(assertInstanceOf(Property.class, pattern.getProperties().getFirst()).isShorthand());
    }

    @Test
    public void test_invalid_assignment_target_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("1 = a"));
    }

    @Test
    public void test_compound_assign_pattern_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("[a] += b"));
    }
}
