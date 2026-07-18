package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.EmptyStatement;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NullLiteral;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThisExpression;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UndefinedLiteral;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.WhileStatement;

public class ParserTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    private static Statement firstStatement(String source) {
        return parse(source).getBody().getFirst();
    }

    private static Expression firstExpression(String source) {
        return ((ExpressionStatement) firstStatement(source)).getExpression();
    }

    // An empty program has an empty body
    @Test
    public void test_empty_program() {
        assertTrue(parse("").getBody().isEmpty());
    }

    // A stray semicolon becomes an empty statement
    @Test
    public void test_empty_statement() {
        assertInstanceOf(EmptyStatement.class, firstStatement(";"));
    }

    // Literals parse to their matching nodes
    @Test
    public void test_number_literal() {
        final var num = assertInstanceOf(NumberLiteral.class, firstExpression("42"));
        assertEquals(42.0, num.getValue());
    }

    @Test
    public void test_string_literal() {
        final var str = assertInstanceOf(StringLiteral.class, firstExpression("\"hi\""));
        assertEquals("hi", str.getValue());
    }

    @Test
    public void test_boolean_null_undefined_literals() {
        assertTrue(assertInstanceOf(BooleanLiteral.class, firstExpression("true")).getValue());
        assertInstanceOf(NullLiteral.class, firstExpression("null"));
        assertInstanceOf(UndefinedLiteral.class, firstExpression("undefined"));
    }

    @Test
    public void test_regex_literal() {
        final var re = assertInstanceOf(RegexLiteral.class, firstExpression("/ab+c/gi"));
        assertEquals("ab+c", re.getPattern());
        assertEquals("gi", re.getFlags());
    }

    @Test
    public void test_template_literal_with_interpolation() {
        final var tpl = assertInstanceOf(TemplateLiteral.class, firstExpression("`a${x + 1}b`"));
        assertEquals(2, tpl.getQuasis().size());
        assertEquals(1, tpl.getExpressions().size());
        assertInstanceOf(BinaryExpression.class, tpl.getExpressions().getFirst());
    }

    @Test
    public void test_identifier_and_this() {
        assertEquals("foo", assertInstanceOf(Identifier.class, firstExpression("foo")).getName());
        assertInstanceOf(ThisExpression.class, firstExpression("this"));
    }

    // 1 + 2 * 3 nests the multiplication under the addition
    @Test
    public void test_binary_precedence() {
        final var add = assertInstanceOf(BinaryExpression.class, firstExpression("1 + 2 * 3"));
        assertEquals("+", add.getOperator());
        assertInstanceOf(NumberLiteral.class, add.getLeft());
        final var mul = assertInstanceOf(BinaryExpression.class, add.getRight());
        assertEquals("*", mul.getOperator());
    }

    // Exponentiation is right-associative: 2 ** 3 ** 2 == 2 ** (3 ** 2)
    @Test
    public void test_exponent_right_associative() {
        final var outer = assertInstanceOf(BinaryExpression.class, firstExpression("2 ** 3 ** 2"));
        assertInstanceOf(NumberLiteral.class, outer.getLeft());
        assertInstanceOf(BinaryExpression.class, outer.getRight());
    }

    @Test
    public void test_logical_and_nullish() {
        assertEquals("&&", assertInstanceOf(LogicalExpression.class, firstExpression("a && b")).getOperator());
        assertEquals("||", assertInstanceOf(LogicalExpression.class, firstExpression("a || b")).getOperator());
        assertEquals("??", assertInstanceOf(LogicalExpression.class, firstExpression("a ?? b")).getOperator());
    }

    @Test
    public void test_instanceof_and_in_operators() {
        assertEquals("instanceof",
                assertInstanceOf(BinaryExpression.class, firstExpression("a instanceof B")).getOperator());
        assertEquals("in", assertInstanceOf(BinaryExpression.class, firstExpression("k in obj")).getOperator());
    }

    @Test
    public void test_unary_prefix() {
        final var not = assertInstanceOf(UnaryExpression.class, firstExpression("!x"));
        assertEquals("!", not.getOperator());
        assertTrue(not.isPrefix());
        assertEquals("-", assertInstanceOf(UnaryExpression.class, firstExpression("-x")).getOperator());
        assertEquals("~", assertInstanceOf(UnaryExpression.class, firstExpression("~x")).getOperator());
        assertEquals("+", assertInstanceOf(UnaryExpression.class, firstExpression("+x")).getOperator());
    }

    @Test
    public void test_typeof_void_delete() {
        assertEquals("typeof", assertInstanceOf(UnaryExpression.class, firstExpression("typeof x")).getOperator());
        assertEquals("void", assertInstanceOf(UnaryExpression.class, firstExpression("void 0")).getOperator());
        assertEquals("delete", assertInstanceOf(UnaryExpression.class, firstExpression("delete a.b")).getOperator());
    }

    @Test
    public void test_update_prefix_and_postfix() {
        final var pre = assertInstanceOf(UpdateExpression.class, firstExpression("++x"));
        assertTrue(pre.isPrefix());
        assertEquals("++", pre.getOperator());
        final var post = assertInstanceOf(UpdateExpression.class, firstExpression("x--"));
        assertEquals("--", post.getOperator());
        assertFalse(post.isPrefix());
    }

    @Test
    public void test_assignment_and_compound_assignment() {
        final var assign = assertInstanceOf(AssignmentExpression.class, firstExpression("x = 1"));
        assertEquals("=", assign.getOperator());
        assertInstanceOf(Identifier.class, assign.getTarget());
        assertEquals("+=", assertInstanceOf(AssignmentExpression.class, firstExpression("x += 1")).getOperator());
    }

    @Test
    public void test_assignment_to_member() {
        final var assign = assertInstanceOf(AssignmentExpression.class, firstExpression("a.b = 1"));
        assertInstanceOf(MemberExpression.class, assign.getTarget());
    }

    @Test
    public void test_conditional_ternary() {
        final var cond = assertInstanceOf(ConditionalExpression.class, firstExpression("a ? b : c"));
        assertInstanceOf(Identifier.class, cond.getTest());
        assertInstanceOf(Identifier.class, cond.getConsequent());
        assertInstanceOf(Identifier.class, cond.getAlternate());
    }

    @Test
    public void test_call_expression() {
        final var call = assertInstanceOf(CallExpression.class, firstExpression("f(1, 2)"));
        assertInstanceOf(Identifier.class, call.getCallee());
        assertEquals(2, call.getArguments().size());
    }

    @Test
    public void test_call_with_no_arguments() {
        assertTrue(assertInstanceOf(CallExpression.class, firstExpression("f()")).getArguments().isEmpty());
    }

    @Test
    public void test_member_dot_and_computed() {
        final var dot = assertInstanceOf(MemberExpression.class, firstExpression("a.b"));
        assertFalse(dot.isComputed());
        assertEquals("b", ((Identifier) dot.getProperty()).getName());
        final var computed = assertInstanceOf(MemberExpression.class, firstExpression("a[b]"));
        assertTrue(computed.isComputed());
    }

    @Test
    public void test_member_with_keyword_name() {
        final var dot = assertInstanceOf(MemberExpression.class, firstExpression("a.default"));
        assertEquals("default", ((Identifier) dot.getProperty()).getName());
    }

    @Test
    public void test_optional_chaining() {
        final var opt = assertInstanceOf(MemberExpression.class, firstExpression("a?.b"));
        assertTrue(opt.isOptional());
        final var optComputed = assertInstanceOf(MemberExpression.class, firstExpression("a?.[b]"));
        assertTrue(optComputed.isOptional());
        assertTrue(optComputed.isComputed());
        assertInstanceOf(CallExpression.class, firstExpression("a?.(b)"));
    }

    @Test
    public void test_chained_call_and_member() {
        final var call = assertInstanceOf(CallExpression.class, firstExpression("a.b().c(1)"));
        final var callee = assertInstanceOf(MemberExpression.class, call.getCallee());
        assertEquals("c", ((Identifier) callee.getProperty()).getName());
    }

    @Test
    public void test_new_expression() {
        final var neu = assertInstanceOf(NewExpression.class, firstExpression("new Foo(1)"));
        assertInstanceOf(Identifier.class, neu.getCallee());
        assertEquals(1, neu.getArguments().size());
    }

    @Test
    public void test_new_without_arguments_and_member_callee() {
        assertTrue(assertInstanceOf(NewExpression.class, firstExpression("new Foo")).getArguments().isEmpty());
        final var neu = assertInstanceOf(NewExpression.class, firstExpression("new a.b.C()"));
        assertInstanceOf(MemberExpression.class, neu.getCallee());
    }

    @Test
    public void test_array_literal() {
        final var arr = assertInstanceOf(ArrayExpression.class, firstExpression("[1, 2, 3]"));
        assertEquals(3, arr.getElements().size());
        assertTrue(assertInstanceOf(ArrayExpression.class, firstExpression("[]")).getElements().isEmpty());
        assertEquals(2, assertInstanceOf(ArrayExpression.class, firstExpression("[1, 2,]")).getElements().size());
    }

    @Test
    public void test_object_literal_and_shorthand() {
        final var obj = assertInstanceOf(ObjectExpression.class, firstExpression("({ a: 1, b: 2 })"));
        assertEquals(2, obj.getProperties().size());
        assertTrue(assertInstanceOf(ObjectExpression.class, firstExpression("({})")).getProperties().isEmpty());
        final var shorthand = assertInstanceOf(ObjectExpression.class, firstExpression("({ x })"));
        final Property prop = shorthand.getProperties().getFirst();
        assertTrue(prop.isShorthand());
    }

    @Test
    public void test_object_computed_string_and_number_keys() {
        final var obj = assertInstanceOf(ObjectExpression.class, firstExpression("({ [k]: 1, \"s\": 2, 3: 4 })"));
        assertTrue(obj.getProperties().getFirst().isComputed());
        assertInstanceOf(StringLiteral.class, obj.getProperties().get(1).getKey());
        assertInstanceOf(NumberLiteral.class, obj.getProperties().get(2).getKey());
    }

    @Test
    public void test_arrow_single_param() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("x => x + 1"));
        assertEquals(1, arrow.getParams().size());
        assertTrue(arrow.isExpressionBody());
        assertInstanceOf(BinaryExpression.class, arrow.getBody());
    }

    @Test
    public void test_arrow_paren_params_and_block_body() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("(a, b) => { return a; }"));
        assertEquals(2, arrow.getParams().size());
        assertFalse(arrow.isExpressionBody());
        assertInstanceOf(BlockStatement.class, arrow.getBody());
    }

    @Test
    public void test_arrow_no_params() {
        assertTrue(assertInstanceOf(ArrowFunctionExpression.class, firstExpression("() => 0")).getParams().isEmpty());
    }

    @Test
    public void test_function_expression_named_and_anonymous() {
        final var named = assertInstanceOf(FunctionExpression.class, firstExpression("(function f(a) { return a; })"));
        assertEquals("f", named.getName().getName());
        assertEquals(1, named.getParams().size());
        final var anon = assertInstanceOf(FunctionExpression.class, firstExpression("(function () {})"));
        assertNull(anon.getName());
    }

    @Test
    public void test_grouping_expression() {
        assertInstanceOf(BinaryExpression.class, firstExpression("(1 + 2)"));
    }

    // Statements

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
    public void test_while_statement() {
        final var loop = assertInstanceOf(WhileStatement.class, firstStatement("while (x < 10) x++;"));
        assertInstanceOf(BinaryExpression.class, loop.getTest());
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
    public void test_try_catch() {
        final var stmt = assertInstanceOf(TryStatement.class, firstStatement("try { x; } catch (e) { y; }"));
        assertInstanceOf(BlockStatement.class, stmt.getBlock());
        assertEquals("e", stmt.getHandler().getParam().getName());
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
    public void test_catch_optional_binding() {
        final var stmt = assertInstanceOf(TryStatement.class, firstStatement("try { x; } catch { y; }"));
        assertNull(stmt.getHandler().getParam());
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
        assertInstanceOf(BreakStatement.class, block.getBody().get(0));
        assertInstanceOf(ContinueStatement.class, block.getBody().get(1));
    }

    @Test
    public void test_function_declaration() {
        final var fn = assertInstanceOf(FunctionDeclaration.class,
                firstStatement("function add(a, b) { return a + b; }"));
        assertEquals("add", fn.getName().getName());
        assertEquals(2, fn.getParams().size());
    }

    @Test
    public void test_optional_semicolons() {
        assertEquals(2, parse("let x = 1\nx + 1").getBody().size());
        assertEquals(2, parse("let x = 1;\nx + 1;").getBody().size());
    }

    @Test
    public void test_trailing_comma_in_params_and_arguments() {
        assertEquals(2,
                assertInstanceOf(FunctionDeclaration.class, firstStatement("function f(a, b,) {}")).getParams().size());
        assertEquals(2, assertInstanceOf(CallExpression.class, firstExpression("f(1, 2,)")).getArguments().size());
    }

    // Negative tests

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
        assertThrows(UnexpectedTokenException.class, () -> parse("var undefined"));
        assertThrows(UnexpectedTokenException.class, () -> parse("var /a/g"));
        assertThrows(UnexpectedTokenException.class, () -> parse("var `t`"));
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
    public void test_switch_missing_colon_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("switch (x) { case 1 a; }"));
    }

    @Test
    public void test_unterminated_switch_throws_end_of_input() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("switch (x) { case 1:"));
    }

    // Parsing from a LexResult reports the offending token's line and column
    @Test
    public void test_parse_with_positions_reports_line_and_column() {
        final var ex = assertThrows(UnexpectedTokenException.class,
                () -> Parser.parse(Lexer.lexWithPositions("let x = ;")));
        assertTrue(ex.getMessage().contains("line: 1, column: 9"), ex.getMessage());
    }

    // A syntax error on a later line reports that line, not line 1
    @Test
    public void test_parse_with_positions_reports_later_line() {
        final var ex = assertThrows(UnexpectedTokenException.class,
                () -> Parser.parse(Lexer.lexWithPositions("var a = 1;\nvar = 2;")));
        assertTrue(ex.getMessage().contains("line: 2"), ex.getMessage());
    }

    // End-of-input errors also carry a line and column when positions are present
    @Test
    public void test_parse_with_positions_reports_end_of_input_location() {
        final var ex = assertThrows(UnexpectedEndOfInputException.class,
                () -> Parser.parse(Lexer.lexWithPositions("1 +")));
        assertTrue(ex.getMessage().contains("Unexpected end of input at line: 1, column: 4"), ex.getMessage());
    }

    // Without positions the parser falls back to the token-index message
    @Test
    public void test_parse_without_positions_uses_index_message() {
        final var ex = assertThrows(UnexpectedTokenException.class, () -> Parser.parse(Lexer.lex("let x = ;")));
        assertTrue(ex.getMessage().contains("at index:"), ex.getMessage());
    }

    // Without positions an end-of-input error carries the plain message
    @Test
    public void test_parse_without_positions_end_of_input_plain_message() {
        final var ex = assertThrows(UnexpectedEndOfInputException.class, () -> Parser.parse(Lexer.lex("1 +")));
        assertEquals("Unexpected end of input", ex.getMessage());
    }
}
