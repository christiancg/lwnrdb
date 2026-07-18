package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.UnexpectedEndOfInputException;
import org.techhouse.simplejs.exceptions.UnexpectedTokenException;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.ArrowFunctionExpression;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BlockStatement;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.BreakStatement;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ClassExpression;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.ContinueStatement;
import org.techhouse.simplejs.nodes.EmptyStatement;
import org.techhouse.simplejs.nodes.ExportAllDeclaration;
import org.techhouse.simplejs.nodes.ExportDefaultDeclaration;
import org.techhouse.simplejs.nodes.ExportNamedDeclaration;
import org.techhouse.simplejs.nodes.ExportSpecifier;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FieldDefinition;
import org.techhouse.simplejs.nodes.ForInStatement;
import org.techhouse.simplejs.nodes.ForOfStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.ImportDeclaration;
import org.techhouse.simplejs.nodes.ImportDefaultSpecifier;
import org.techhouse.simplejs.nodes.ImportNamespaceSpecifier;
import org.techhouse.simplejs.nodes.ImportSpecifier;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NullLiteral;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.ReturnStatement;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.SuperExpression;
import org.techhouse.simplejs.nodes.SwitchStatement;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThisExpression;
import org.techhouse.simplejs.nodes.ThrowStatement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UndefinedLiteral;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.VariableDeclarator;
import org.techhouse.simplejs.nodes.WhileStatement;
import org.techhouse.simplejs.nodes.YieldExpression;

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
        final var prop = assertInstanceOf(Property.class, shorthand.getProperties().getFirst());
        assertTrue(prop.isShorthand());
    }

    @Test
    public void test_object_computed_string_and_number_keys() {
        final var obj = assertInstanceOf(ObjectExpression.class, firstExpression("({ [k]: 1, \"s\": 2, 3: 4 })"));
        assertTrue(assertInstanceOf(Property.class, obj.getProperties().getFirst()).isComputed());
        assertInstanceOf(StringLiteral.class, assertInstanceOf(Property.class, obj.getProperties().get(1)).getKey());
        assertInstanceOf(NumberLiteral.class, assertInstanceOf(Property.class, obj.getProperties().get(2)).getKey());
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

    // An empty class declaration has a name, no superclass and no members
    @Test
    public void test_empty_class() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C {}"));
        assertEquals("C", decl.getId().getName());
        assertNull(decl.getSuperClass());
        assertTrue(decl.getBody().getMembers().isEmpty());
    }

    // extends with a plain identifier heritage
    @Test
    public void test_class_extends() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C extends B {}"));
        final var superClass = assertInstanceOf(Identifier.class, decl.getSuperClass());
        assertEquals("B", superClass.getName());
    }

    // extends accepts a member expression heritage
    @Test
    public void test_class_extends_member() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C extends a.B {}"));
        assertInstanceOf(MemberExpression.class, decl.getSuperClass());
    }

    // A plain method is a non-static method with a function value
    @Test
    public void test_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("m", ((Identifier) method.getKey()).getName());
        assertEquals("method", method.getKind());
        assertFalse(method.isStatic());
        assertInstanceOf(FunctionExpression.class, method.getValue());
    }

    // A constructor member resolves to the constructor kind
    @Test
    public void test_class_constructor() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { constructor(x) {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("constructor", method.getKind());
        assertEquals(1, method.getValue().getParams().size());
    }

    // A static method carries the static flag
    @Test
    public void test_static_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.isStatic());
        assertEquals("method", method.getKind());
    }

    // Getters and setters resolve to get/set kinds
    @Test
    public void test_getter_setter() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { get x() {} set x(v) {} }"));
        final var members = decl.getBody().getMembers();
        assertEquals("get", ((MethodDefinition) members.get(0)).getKind());
        assertEquals("set", ((MethodDefinition) members.get(1)).getKind());
    }

    // A computed method key sets the computed flag
    @Test
    public void test_computed_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { [a + b]() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.isComputed());
        assertInstanceOf(BinaryExpression.class, method.getKey());
    }

    // A class field with an initializer
    @Test
    public void test_class_field() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { x = 1; }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("x", ((Identifier) field.getKey()).getName());
        assertInstanceOf(NumberLiteral.class, field.getValue());
        assertFalse(field.isStatic());
    }

    // A class field without an initializer has a null value
    @Test
    public void test_class_field_no_init() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { x }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertNull(field.getValue());
    }

    // A static field carries the static flag
    @Test
    public void test_static_field() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static x = 1; }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(field.isStatic());
        assertInstanceOf(NumberLiteral.class, field.getValue());
    }

    // A member literally named "static" is a method, not a static modifier
    @Test
    public void test_member_named_static() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertFalse(method.isStatic());
        assertEquals("static", ((Identifier) method.getKey()).getName());
    }

    // A member literally named "get" is a plain method, not an accessor
    @Test
    public void test_member_named_get() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { get() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("method", method.getKind());
        assertEquals("get", ((Identifier) method.getKey()).getName());
    }

    // An anonymous class expression has a null id
    @Test
    public void test_class_expression_anonymous() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("const C = class {};"));
        final var expr = assertInstanceOf(ClassExpression.class, decl.getDeclarations().getFirst().getInit());
        assertNull(expr.getId());
    }

    // A named class expression carries its name
    @Test
    public void test_class_expression_named() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("const C = class Named {};"));
        final var expr = assertInstanceOf(ClassExpression.class, decl.getDeclarations().getFirst().getInit());
        assertEquals("Named", expr.getId().getName());
    }

    // super(...) parses to a call over a super expression
    @Test
    public void test_super_call() {
        final var call = assertInstanceOf(CallExpression.class, firstExpression("super(x)"));
        assertInstanceOf(SuperExpression.class, call.getCallee());
    }

    // super.m() parses to a call over a member access on super
    @Test
    public void test_super_member() {
        final var call = assertInstanceOf(CallExpression.class, firstExpression("super.m()"));
        final var member = assertInstanceOf(MemberExpression.class, call.getCallee());
        assertInstanceOf(SuperExpression.class, member.getObject());
    }

    // Stray semicolons between members are skipped
    @Test
    public void test_stray_semicolons_in_body() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { ; m() {}; }"));
        assertEquals(1, decl.getBody().getMembers().size());
    }

    // A class declaration requires a name
    @Test
    public void test_class_declaration_requires_name() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class {}"));
    }

    // An unterminated class body reports end of input
    @Test
    public void test_unterminated_class_body() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("class C {"));
    }

    // A getter cannot be a field
    @Test
    public void test_getter_cannot_be_field() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class C { get x = 1 }"));
    }

    // Phase 4 — async & generators

    // await parses to an AwaitExpression inside an async function
    @Test
    public void test_await_unary() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("async function f() { await g(); }"));
        assertTrue(fn.isAsync());
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        assertInstanceOf(AwaitExpression.class, stmt.getExpression());
    }

    // await binds tighter than binary: await a + b is (await a) + b
    @Test
    public void test_await_binds_tighter_than_binary() {
        final var fn = assertInstanceOf(FunctionDeclaration.class,
                firstStatement("async function f() { await a + b; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var add = assertInstanceOf(BinaryExpression.class, stmt.getExpression());
        assertInstanceOf(AwaitExpression.class, add.getLeft());
    }

    // yield with an argument in a generator
    @Test
    public void test_yield_with_argument() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { yield 1; }"));
        assertTrue(fn.isGenerator());
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var yieldExpr = assertInstanceOf(YieldExpression.class, stmt.getExpression());
        assertInstanceOf(NumberLiteral.class, yieldExpr.getArgument());
        assertFalse(yieldExpr.isDelegate());
    }

    // yield* delegates to another iterable
    @Test
    public void test_yield_delegate() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { yield* xs; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var yieldExpr = assertInstanceOf(YieldExpression.class, stmt.getExpression());
        assertTrue(yieldExpr.isDelegate());
        assertInstanceOf(Identifier.class, yieldExpr.getArgument());
    }

    // A bare yield has no argument
    @Test
    public void test_yield_no_argument() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { yield; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var yieldExpr = assertInstanceOf(YieldExpression.class, stmt.getExpression());
        assertNull(yieldExpr.getArgument());
        assertFalse(yieldExpr.isDelegate());
    }

    // yield appears at the assignment right-hand side
    @Test
    public void test_yield_as_assignment_rhs() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { x = yield 2; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var assign = assertInstanceOf(AssignmentExpression.class, stmt.getExpression());
        assertInstanceOf(YieldExpression.class, assign.getValue());
    }

    // async function declaration carries the async flag only
    @Test
    public void test_async_function_declaration() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("async function f() {}"));
        assertTrue(fn.isAsync());
        assertFalse(fn.isGenerator());
    }

    // async function expression carries the async flag
    @Test
    public void test_async_function_expression() {
        final var expr = assertInstanceOf(FunctionExpression.class, firstExpression("(async function () {})"));
        assertTrue(expr.isAsync());
        assertFalse(expr.isGenerator());
    }

    // generator function declaration carries the generator flag only
    @Test
    public void test_generator_function_declaration() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* f() {}"));
        assertTrue(fn.isGenerator());
        assertFalse(fn.isAsync());
    }

    // generator function expression carries the generator flag
    @Test
    public void test_generator_function_expression() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("const g = function*() {};"));
        final var expr = assertInstanceOf(FunctionExpression.class, decl.getDeclarations().getFirst().getInit());
        assertTrue(expr.isGenerator());
    }

    // async generator function carries both flags
    @Test
    public void test_async_generator_function() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("async function* f() {}"));
        assertTrue(fn.isAsync());
        assertTrue(fn.isGenerator());
    }

    // async single-parameter arrow
    @Test
    public void test_async_arrow_single_param() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("async x => x"));
        assertTrue(arrow.isAsync());
        assertEquals(1, arrow.getParams().size());
        assertTrue(arrow.isExpressionBody());
    }

    // async parenthesized arrow
    @Test
    public void test_async_arrow_paren_params() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("async (a, b) => a"));
        assertTrue(arrow.isAsync());
        assertEquals(2, arrow.getParams().size());
    }

    // async arrow with a block body
    @Test
    public void test_async_arrow_block_body() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("async () => {}"));
        assertTrue(arrow.isAsync());
        assertFalse(arrow.isExpressionBody());
    }

    // async class method sets async on its function value
    @Test
    public void test_async_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.getValue().isAsync());
        assertFalse(method.getValue().isGenerator());
        assertEquals("method", method.getKind());
    }

    // generator class method sets generator on its function value
    @Test
    public void test_generator_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { *m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.getValue().isGenerator());
    }

    // async generator class method sets both flags
    @Test
    public void test_async_generator_class_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async *m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.getValue().isAsync());
        assertTrue(method.getValue().isGenerator());
    }

    // static async method carries the static flag and async on its value
    @Test
    public void test_static_async_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { static async m() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertTrue(method.isStatic());
        assertTrue(method.getValue().isAsync());
    }

    // A member literally named "async" is a method, not a modifier
    @Test
    public void test_member_named_async() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertFalse(method.getValue().isAsync());
        assertEquals("async", ((Identifier) method.getKey()).getName());
    }

    // A field literally named "async" is a field, not a modifier
    @Test
    public void test_field_named_async() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async = 1 }"));
        final var field = assertInstanceOf(FieldDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("async", ((Identifier) field.getKey()).getName());
    }

    // An async member named constructor is a plain method, not the constructor
    @Test
    public void test_async_constructor_is_method() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async constructor() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("method", method.getKind());
    }

    // async not followed by function/arrow is an error
    @Test
    public void test_async_without_function_or_arrow_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("async 1"));
        assertThrows(UnexpectedTokenException.class, () -> parse("async +"));
    }

    // yield* requires an argument
    @Test
    public void test_yield_delegate_without_argument_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("function* g() { yield*; }"));
    }

    // An async getter is invalid
    @Test
    public void test_async_getter_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class C { async get x() {} }"));
    }

    // get before an async member is invalid
    @Test
    public void test_get_before_async_member_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("class C { get async foo() {} }"));
    }

    // A spread element in an array literal wraps its argument
    @Test
    public void test_array_spread() {
        final var array = assertInstanceOf(ArrayExpression.class, firstExpression("[1, ...rest]"));
        assertEquals(2, array.getElements().size());
        final var spread = assertInstanceOf(SpreadElement.class, array.getElements().get(1));
        assertEquals("rest", assertInstanceOf(Identifier.class, spread.getArgument()).getName());
    }

    // Array elisions produce null elements, but a trailing comma does not
    @Test
    public void test_array_holes_and_trailing_comma() {
        final var holed = assertInstanceOf(ArrayExpression.class, firstExpression("[a, , b]"));
        assertEquals(3, holed.getElements().size());
        assertNull(holed.getElements().get(1));
        assertEquals(1, assertInstanceOf(ArrayExpression.class, firstExpression("[a,]")).getElements().size());
        final var leading = assertInstanceOf(ArrayExpression.class, firstExpression("[,]"));
        assertEquals(1, leading.getElements().size());
        assertNull(leading.getElements().getFirst());
    }

    // A spread argument in a call wraps its argument
    @Test
    public void test_call_spread_argument() {
        final var call = assertInstanceOf(CallExpression.class, firstExpression("f(a, ...xs)"));
        assertEquals(2, call.getArguments().size());
        final var spread = assertInstanceOf(SpreadElement.class, call.getArguments().get(1));
        assertEquals("xs", assertInstanceOf(Identifier.class, spread.getArgument()).getName());
    }

    // A spread element in an object literal is kept alongside properties
    @Test
    public void test_object_spread() {
        final var obj = assertInstanceOf(ObjectExpression.class, firstExpression("({ ...o, a: 1 })"));
        assertEquals(2, obj.getProperties().size());
        final var spread = assertInstanceOf(SpreadElement.class, obj.getProperties().getFirst());
        assertEquals("o", assertInstanceOf(Identifier.class, spread.getArgument()).getName());
        assertInstanceOf(Property.class, obj.getProperties().get(1));
    }

    // A rest parameter is the last parameter of a function declaration
    @Test
    public void test_rest_param_function() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function f(a, ...rest) {}"));
        assertEquals(2, fn.getParams().size());
        final var rest = assertInstanceOf(RestElement.class, fn.getParams().get(1));
        assertEquals("rest", assertInstanceOf(Identifier.class, rest.getArgument()).getName());
    }

    // A rest parameter works in an arrow function
    @Test
    public void test_rest_param_arrow() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("(a, ...rest) => a"));
        assertEquals(2, arrow.getParams().size());
        assertInstanceOf(RestElement.class, arrow.getParams().get(1));
    }

    // A parameter after a rest parameter is invalid
    @Test
    public void test_param_after_rest_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("function f(...rest, a) {}"));
    }

    // A spread with no argument is invalid
    @Test
    public void test_spread_without_argument_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("f(...)"));
    }

    private static VariableDeclarator firstDeclarator(String source) {
        return ((VariableDeclaration) firstStatement(source)).getDeclarations().getFirst();
    }

    // An array pattern binds the declarator id
    @Test
    public void test_array_pattern_declaration() {
        final var pattern = assertInstanceOf(ArrayPattern.class, firstDeclarator("const [a, b] = arr").getId());
        assertEquals(2, pattern.getElements().size());
        assertEquals("a", assertInstanceOf(Identifier.class, pattern.getElements().getFirst()).getName());
    }

    // An array pattern keeps holes and a trailing rest element
    @Test
    public void test_array_pattern_hole_and_rest() {
        final var pattern = assertInstanceOf(ArrayPattern.class, firstDeclarator("const [a, , ...r] = arr").getId());
        assertEquals(3, pattern.getElements().size());
        assertNull(pattern.getElements().get(1));
        final var rest = assertInstanceOf(RestElement.class, pattern.getElements().get(2));
        assertEquals("r", assertInstanceOf(Identifier.class, rest.getArgument()).getName());
    }

    // An object pattern binds two shorthand properties
    @Test
    public void test_object_pattern_declaration() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a, b} = o").getId());
        assertEquals(2, pattern.getProperties().size());
        assertTrue(assertInstanceOf(Property.class, pattern.getProperties().getFirst()).isShorthand());
    }

    // An object pattern supports renamed keys and defaults
    @Test
    public void test_object_pattern_renamed_and_default() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a: x, b = 2} = o").getId());
        final var renamed = assertInstanceOf(Property.class, pattern.getProperties().getFirst());
        assertEquals("x", assertInstanceOf(Identifier.class, renamed.getValue()).getName());
        final var defaulted = assertInstanceOf(Property.class, pattern.getProperties().get(1));
        final var assignment = assertInstanceOf(AssignmentPattern.class, defaulted.getValue());
        assertEquals(2.0, assertInstanceOf(NumberLiteral.class, assignment.getRight()).getValue());
    }

    // An object pattern keeps a trailing rest element
    @Test
    public void test_object_pattern_rest() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a, ...r} = o").getId());
        final var rest = assertInstanceOf(RestElement.class, pattern.getProperties().get(1));
        assertEquals("r", assertInstanceOf(Identifier.class, rest.getArgument()).getName());
    }

    // Patterns nest inside each other
    @Test
    public void test_nested_pattern() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a: [b, {c}]} = o").getId());
        final var outer = assertInstanceOf(Property.class, pattern.getProperties().getFirst());
        final var inner = assertInstanceOf(ArrayPattern.class, outer.getValue());
        assertInstanceOf(Identifier.class, inner.getElements().getFirst());
        assertInstanceOf(ObjectPattern.class, inner.getElements().get(1));
    }

    // Function parameters accept defaults and patterns
    @Test
    public void test_pattern_and_default_params() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function f(a = 1, {b}, [c]) {}"));
        assertEquals(3, fn.getParams().size());
        final var defaulted = assertInstanceOf(AssignmentPattern.class, fn.getParams().getFirst());
        assertEquals("a", assertInstanceOf(Identifier.class, defaulted.getLeft()).getName());
        assertInstanceOf(ObjectPattern.class, fn.getParams().get(1));
        assertInstanceOf(ArrayPattern.class, fn.getParams().get(2));
    }

    // Arrow parameters accept patterns
    @Test
    public void test_arrow_pattern_params() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("({a}, [b]) => a"));
        assertInstanceOf(ObjectPattern.class, arrow.getParams().getFirst());
        assertInstanceOf(ArrayPattern.class, arrow.getParams().get(1));
    }

    // An array destructuring assignment reinterprets the LHS into a pattern
    @Test
    public void test_array_assignment_pattern() {
        final var assign = assertInstanceOf(AssignmentExpression.class, firstExpression("[a, b] = arr"));
        final var pattern = assertInstanceOf(ArrayPattern.class, assign.getTarget());
        assertEquals(2, pattern.getElements().size());
    }

    // A member expression is a valid leaf inside an assignment pattern
    @Test
    public void test_assignment_pattern_member_leaf() {
        final var assign = assertInstanceOf(AssignmentExpression.class, firstExpression("[obj.x] = arr"));
        final var pattern = assertInstanceOf(ArrayPattern.class, assign.getTarget());
        assertInstanceOf(MemberExpression.class, pattern.getElements().getFirst());
    }

    // An object destructuring assignment reinterprets the LHS into a pattern
    @Test
    public void test_object_assignment_pattern() {
        final var assign = assertInstanceOf(AssignmentExpression.class, firstExpression("({a, b} = o)"));
        assertInstanceOf(ObjectPattern.class, assign.getTarget());
    }

    // A cover-initialized name in a destructuring assignment becomes an assignment pattern
    @Test
    public void test_assignment_pattern_with_default() {
        final var assign = assertInstanceOf(AssignmentExpression.class, firstExpression("({a = 1} = o)"));
        final var pattern = assertInstanceOf(ObjectPattern.class, assign.getTarget());
        final var property = assertInstanceOf(Property.class, pattern.getProperties().getFirst());
        assertInstanceOf(AssignmentPattern.class, property.getValue());
    }

    // A catch clause binds a pattern
    @Test
    public void test_catch_pattern() {
        final var tryStatement = assertInstanceOf(TryStatement.class, firstStatement("try {} catch ({message}) {}"));
        final var handler = assertInstanceOf(CatchClause.class, tryStatement.getHandler());
        assertInstanceOf(ObjectPattern.class, handler.getParam());
    }

    // A for-of loop declares a pattern binding
    @Test
    public void test_for_of_pattern_declaration() {
        final var forOf = assertInstanceOf(ForOfStatement.class, firstStatement("for (const [a, b] of x) {}"));
        final var declaration = assertInstanceOf(VariableDeclaration.class, forOf.getLeft());
        assertInstanceOf(ArrayPattern.class, declaration.getDeclarations().getFirst().getId());
    }

    // A for-of loop reinterprets an expression LHS into a pattern
    @Test
    public void test_for_of_pattern_assignment() {
        final var forOf = assertInstanceOf(ForOfStatement.class, firstStatement("for ([a] of x) {}"));
        assertInstanceOf(ArrayPattern.class, forOf.getLeft());
    }

    // Empty patterns parse
    @Test
    public void test_empty_patterns() {
        assertInstanceOf(ObjectPattern.class, firstDeclarator("const {} = o").getId());
        assertInstanceOf(ArrayPattern.class, firstDeclarator("const [] = a").getId());
    }

    // A rest element before the end of an array pattern is invalid
    @Test
    public void test_rest_before_end_array_pattern_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("const [...r, a] = x"));
    }

    // A non-target expression cannot be an assignment LHS
    @Test
    public void test_invalid_assignment_target_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("1 = a"));
    }

    // A compound assignment cannot target a pattern
    @Test
    public void test_compound_assign_pattern_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("[a] += b"));
    }

    // A bare import has no specifiers, only a source
    @Test
    public void test_import_bare() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import \"mod\";"));
        assertTrue(decl.getSpecifiers().isEmpty());
        assertEquals("mod", decl.getSource().getValue());
    }

    // A default import binds a single local name
    @Test
    public void test_import_default() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import def from \"mod\";"));
        assertEquals(1, decl.getSpecifiers().size());
        final var spec = assertInstanceOf(ImportDefaultSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("def", spec.getLocal().getName());
        assertEquals("mod", decl.getSource().getValue());
    }

    // A namespace import binds `* as name`
    @Test
    public void test_import_namespace() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import * as ns from \"mod\";"));
        final var spec = assertInstanceOf(ImportNamespaceSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("ns", spec.getLocal().getName());
    }

    // Named imports carry imported and local names, aliased via `as`
    @Test
    public void test_import_named() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import { a, b as c } from \"mod\";"));
        assertEquals(2, decl.getSpecifiers().size());
        final var first = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("a", assertInstanceOf(Identifier.class, first.getImported()).getName());
        assertEquals("a", first.getLocal().getName());
        final var second = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().get(1));
        assertEquals("b", assertInstanceOf(Identifier.class, second.getImported()).getName());
        assertEquals("c", second.getLocal().getName());
    }

    // A keyword may name an imported binding (`default as x`)
    @Test
    public void test_import_named_keyword_name() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import { default as x } from \"mod\";"));
        final var spec = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("default", assertInstanceOf(Identifier.class, spec.getImported()).getName());
        assertEquals("x", spec.getLocal().getName());
    }

    // A string may name an imported binding (`"a" as x`)
    @Test
    public void test_import_named_string_name() {
        final var decl = assertInstanceOf(ImportDeclaration.class,
                firstStatement("import { \"a\" as x } from \"mod\";"));
        final var spec = assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().getFirst());
        assertEquals("a", assertInstanceOf(StringLiteral.class, spec.getImported()).getValue());
        assertEquals("x", spec.getLocal().getName());
    }

    // A default import combines with a named group
    @Test
    public void test_import_default_plus_named() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import def, { a } from \"mod\";"));
        assertEquals(2, decl.getSpecifiers().size());
        assertInstanceOf(ImportDefaultSpecifier.class, decl.getSpecifiers().getFirst());
        assertInstanceOf(ImportSpecifier.class, decl.getSpecifiers().get(1));
    }

    // A default import combines with a namespace import
    @Test
    public void test_import_default_plus_namespace() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import def, * as ns from \"mod\";"));
        assertEquals(2, decl.getSpecifiers().size());
        assertInstanceOf(ImportDefaultSpecifier.class, decl.getSpecifiers().getFirst());
        assertInstanceOf(ImportNamespaceSpecifier.class, decl.getSpecifiers().get(1));
    }

    // Empty braces import nothing but still require a source
    @Test
    public void test_import_empty_braces() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import {} from \"mod\";"));
        assertTrue(decl.getSpecifiers().isEmpty());
        assertEquals("mod", decl.getSource().getValue());
    }

    // A trailing comma inside the named-import braces is allowed
    @Test
    public void test_import_named_trailing_comma() {
        final var decl = assertInstanceOf(ImportDeclaration.class, firstStatement("import { a, } from \"mod\";"));
        assertEquals(1, decl.getSpecifiers().size());
    }

    // Named exports carry local and exported names, aliased via `as`
    @Test
    public void test_export_named() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export { a, b as c };"));
        assertNull(decl.getDeclaration());
        assertNull(decl.getSource());
        assertEquals(2, decl.getSpecifiers().size());
        final var first = decl.getSpecifiers().getFirst();
        assertEquals("a", assertInstanceOf(Identifier.class, first.getLocal()).getName());
        assertEquals("a", assertInstanceOf(Identifier.class, first.getExported()).getName());
        final var second = decl.getSpecifiers().get(1);
        assertEquals("b", assertInstanceOf(Identifier.class, second.getLocal()).getName());
        assertEquals("c", assertInstanceOf(Identifier.class, second.getExported()).getName());
    }

    // A named export may re-export from another module
    @Test
    public void test_export_named_reexport() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export { a } from \"mod\";"));
        assertEquals("mod", decl.getSource().getValue());
    }

    // A string may name an exported binding (`a as "x"`)
    @Test
    public void test_export_named_string_name() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export { a as \"x\" };"));
        final ExportSpecifier spec = decl.getSpecifiers().getFirst();
        assertEquals("x", assertInstanceOf(StringLiteral.class, spec.getExported()).getValue());
    }

    // export * re-exports everything with no local name
    @Test
    public void test_export_all() {
        final var decl = assertInstanceOf(ExportAllDeclaration.class, firstStatement("export * from \"mod\";"));
        assertNull(decl.getExported());
        assertEquals("mod", decl.getSource().getValue());
    }

    // export * as ns names the re-exported namespace
    @Test
    public void test_export_all_as() {
        final var decl = assertInstanceOf(ExportAllDeclaration.class, firstStatement("export * as ns from \"mod\";"));
        assertEquals("ns", decl.getExported().getName());
    }

    // A default export wraps an arbitrary expression
    @Test
    public void test_export_default_expression() {
        final var decl = assertInstanceOf(ExportDefaultDeclaration.class, firstStatement("export default 1 + 2;"));
        assertInstanceOf(BinaryExpression.class, decl.getDeclaration());
    }

    // A default export accepts a function value
    @Test
    public void test_export_default_function() {
        final var decl = assertInstanceOf(ExportDefaultDeclaration.class,
                firstStatement("export default function f() {}"));
        assertInstanceOf(FunctionExpression.class, decl.getDeclaration());
    }

    // A default export accepts an anonymous class value
    @Test
    public void test_export_default_class() {
        final var decl = assertInstanceOf(ExportDefaultDeclaration.class, firstStatement("export default class {}"));
        assertInstanceOf(ClassExpression.class, decl.getDeclaration());
    }

    // Exporting a var declaration keeps the declaration and no specifiers
    @Test
    public void test_export_var_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export const x = 1;"));
        assertInstanceOf(VariableDeclaration.class, decl.getDeclaration());
        assertTrue(decl.getSpecifiers().isEmpty());
        assertNull(decl.getSource());
    }

    // Exporting a function declaration keeps the declaration
    @Test
    public void test_export_function_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export function f() {}"));
        assertInstanceOf(FunctionDeclaration.class, decl.getDeclaration());
    }

    // Exporting an async function declaration preserves the async flag
    @Test
    public void test_export_async_function_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export async function f() {}"));
        final var fn = assertInstanceOf(FunctionDeclaration.class, decl.getDeclaration());
        assertTrue(fn.isAsync());
    }

    // Exporting a class declaration keeps the declaration
    @Test
    public void test_export_class_declaration() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export class C {}"));
        assertInstanceOf(ClassDeclaration.class, decl.getDeclaration());
    }

    // Empty export braces are allowed
    @Test
    public void test_export_empty_braces() {
        final var decl = assertInstanceOf(ExportNamedDeclaration.class, firstStatement("export {};"));
        assertTrue(decl.getSpecifiers().isEmpty());
    }

    // `from` and `as` remain ordinary identifiers outside module syntax
    @Test
    public void test_from_as_are_contextual() {
        assertInstanceOf(VariableDeclaration.class, firstStatement("let from = 1;"));
        assertInstanceOf(VariableDeclaration.class, firstStatement("const as = 2;"));
    }

    // A named import without a `from` clause is a parse error
    @Test
    public void test_import_missing_from_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import { a } 42;"));
    }

    // A named import that ends before its source is an unexpected end of input
    @Test
    public void test_import_missing_source_eof_throws() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("import { a }"));
    }

    // A non-string import source is a parse error
    @Test
    public void test_import_non_string_source_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import def from 123;"));
    }

    // A namespace import without `as` is a parse error
    @Test
    public void test_import_namespace_missing_as_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import * ns from \"mod\";"));
    }

    // A dangling comma clause after a default import is a parse error
    @Test
    public void test_import_dangling_clause_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("import def, ;"));
    }

    // export * without a source is a parse error
    @Test
    public void test_export_all_missing_from_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("export * ;"));
    }

    // export followed by a non-declaration is a parse error
    @Test
    public void test_export_bad_declaration_throws() {
        assertThrows(UnexpectedTokenException.class, () -> parse("export 123;"));
    }

    // export at end of input is an unexpected end of input
    @Test
    public void test_export_eof_throws() {
        assertThrows(UnexpectedEndOfInputException.class, () -> parse("export"));
    }
}
