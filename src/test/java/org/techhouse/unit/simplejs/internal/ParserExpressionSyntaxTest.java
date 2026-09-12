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
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.AwaitExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.CatchClause;
import org.techhouse.simplejs.nodes.ClassDeclaration;
import org.techhouse.simplejs.nodes.ConditionalExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.LogicalExpression;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.MethodDefinition;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RestElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.VariableDeclarator;
import org.techhouse.simplejs.nodes.YieldExpression;

public class ParserExpressionSyntaxTest {
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
    public void test_empty_program() {
        assertTrue(parse("").getBody().isEmpty());
    }

    @Test
    public void test_binary_precedence() {
        final var add = assertInstanceOf(BinaryExpression.class, firstExpression("1 + 2 * 3"));
        assertEquals("+", add.getOperator());
        assertInstanceOf(NumberLiteral.class, add.getLeft());
        final var mul = assertInstanceOf(BinaryExpression.class, add.getRight());
        assertEquals("*", mul.getOperator());
    }

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
    public void test_unary_prefix() {
        final var not = assertInstanceOf(UnaryExpression.class, firstExpression("!x"));
        assertEquals("!", not.getOperator());
        assertTrue(not.isPrefix());
        assertEquals("-", assertInstanceOf(UnaryExpression.class, firstExpression("-x")).getOperator());
        assertEquals("~", assertInstanceOf(UnaryExpression.class, firstExpression("~x")).getOperator());
        assertEquals("+", assertInstanceOf(UnaryExpression.class, firstExpression("+x")).getOperator());
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
    public void test_object_get_set_as_plain_keys() {
        final var obj = assertInstanceOf(ObjectExpression.class, firstExpression("({ get: 1, set: 2 })"));
        assertEquals("init", assertInstanceOf(Property.class, obj.getProperties().getFirst()).getKind());
        assertEquals("init", assertInstanceOf(Property.class, obj.getProperties().get(1)).getKind());
    }

    @Test
    public void test_arrow_single_param() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("x => x + 1"));
        assertEquals(1, arrow.getParams().size());
        assertTrue(arrow.isExpressionBody());
        assertInstanceOf(BinaryExpression.class, arrow.getBody());
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

    @Test
    public void test_trailing_comma_in_params_and_arguments() {
        assertEquals(2,
                assertInstanceOf(FunctionDeclaration.class, firstStatement("function f(a, b,) {}")).getParams().size());
        assertEquals(2, assertInstanceOf(CallExpression.class, firstExpression("f(1, 2,)")).getArguments().size());
    }

    @Test
    public void test_parse_with_positions_reports_line_and_column() {
        final var ex = assertThrows(UnexpectedTokenException.class,
                () -> Parser.parse(Lexer.lexWithPositions("let x = ;")));
        assertTrue(ex.getMessage().contains("line: 1, column: 9"), ex.getMessage());
    }

    @Test
    public void test_parse_with_positions_reports_later_line() {
        final var ex = assertThrows(UnexpectedTokenException.class,
                () -> Parser.parse(Lexer.lexWithPositions("var a = 1;\nvar = 2;")));
        assertTrue(ex.getMessage().contains("line: 2"), ex.getMessage());
    }

    @Test
    public void test_parse_with_positions_reports_end_of_input_location() {
        final var ex = assertThrows(UnexpectedEndOfInputException.class,
                () -> Parser.parse(Lexer.lexWithPositions("1 +")));
        assertTrue(ex.getMessage().contains("Unexpected end of input at line: 1, column: 4"), ex.getMessage());
    }

    @Test
    public void test_parse_without_positions_uses_index_message() {
        final var ex = assertThrows(UnexpectedTokenException.class, () -> Parser.parse(Lexer.lex("let x = ;")));
        assertTrue(ex.getMessage().contains("at index:"), ex.getMessage());
    }

    @Test
    public void test_parse_without_positions_end_of_input_plain_message() {
        final var ex = assertThrows(UnexpectedEndOfInputException.class, () -> Parser.parse(Lexer.lex("1 +")));
        assertEquals("Unexpected end of input", ex.getMessage());
    }

    @Test
    public void test_getter_setter() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { get x() {} set x(v) {} }"));
        final var members = decl.getBody().getMembers();
        assertEquals("get", ((MethodDefinition) members.get(0)).getKind());
        assertEquals("set", ((MethodDefinition) members.get(1)).getKind());
    }

    @Test
    public void test_member_named_get() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { get() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertEquals("method", method.getKind());
        assertEquals("get", ((Identifier) method.getKey()).getName());
    }

    @Test
    public void test_stray_semicolons_in_body() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { ; m() {}; }"));
        assertEquals(1, decl.getBody().getMembers().size());
    }

    @Test
    public void test_await_unary() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("async function f() { await g(); }"));
        assertTrue(fn.isAsync());
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        assertInstanceOf(AwaitExpression.class, stmt.getExpression());
    }

    @Test
    public void test_await_binds_tighter_than_binary() {
        final var fn = assertInstanceOf(FunctionDeclaration.class,
                firstStatement("async function f() { await a + b; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var add = assertInstanceOf(BinaryExpression.class, stmt.getExpression());
        assertInstanceOf(AwaitExpression.class, add.getLeft());
    }

    @Test
    public void test_yield_with_argument() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { yield 1; }"));
        assertTrue(fn.isGenerator());
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var yieldExpr = assertInstanceOf(YieldExpression.class, stmt.getExpression());
        assertInstanceOf(NumberLiteral.class, yieldExpr.getArgument());
        assertFalse(yieldExpr.isDelegate());
    }

    @Test
    public void test_yield_delegate() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { yield* xs; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var yieldExpr = assertInstanceOf(YieldExpression.class, stmt.getExpression());
        assertTrue(yieldExpr.isDelegate());
        assertInstanceOf(Identifier.class, yieldExpr.getArgument());
    }

    @Test
    public void test_yield_no_argument() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { yield; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var yieldExpr = assertInstanceOf(YieldExpression.class, stmt.getExpression());
        assertNull(yieldExpr.getArgument());
        assertFalse(yieldExpr.isDelegate());
    }

    @Test
    public void test_async_function_expression() {
        final var expr = assertInstanceOf(FunctionExpression.class, firstExpression("(async function () {})"));
        assertTrue(expr.isAsync());
        assertFalse(expr.isGenerator());
    }

    @Test
    public void test_generator_function_expression() {
        final var decl = assertInstanceOf(VariableDeclaration.class, firstStatement("const g = function*() {};"));
        final var expr = assertInstanceOf(FunctionExpression.class, decl.getDeclarations().getFirst().getInit());
        assertTrue(expr.isGenerator());
    }

    @Test
    public void test_async_generator_function() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("async function* f() {}"));
        assertTrue(fn.isAsync());
        assertTrue(fn.isGenerator());
    }

    @Test
    public void test_async_arrow_single_param() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("async x => x"));
        assertTrue(arrow.isAsync());
        assertEquals(1, arrow.getParams().size());
        assertTrue(arrow.isExpressionBody());
    }

    @Test
    public void test_async_arrow_paren_params() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("async (a, b) => a"));
        assertTrue(arrow.isAsync());
        assertEquals(2, arrow.getParams().size());
    }

    @Test
    public void test_member_named_async() {
        final var decl = assertInstanceOf(ClassDeclaration.class, firstStatement("class C { async() {} }"));
        final var method = assertInstanceOf(MethodDefinition.class, decl.getBody().getMembers().getFirst());
        assertFalse(method.getValue().isAsync());
        assertEquals("async", ((Identifier) method.getKey()).getName());
    }

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

    @Test
    public void test_rest_param_function() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function f(a, ...rest) {}"));
        assertEquals(2, fn.getParams().size());
        final var rest = assertInstanceOf(RestElement.class, fn.getParams().get(1));
        assertEquals("rest", assertInstanceOf(Identifier.class, rest.getArgument()).getName());
    }

    @Test
    public void test_rest_param_arrow() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("(a, ...rest) => a"));
        assertEquals(2, arrow.getParams().size());
        assertInstanceOf(RestElement.class, arrow.getParams().get(1));
    }

    private static VariableDeclarator firstDeclarator(String source) {
        return ((VariableDeclaration) firstStatement(source)).getDeclarations().getFirst();
    }

    @Test
    public void test_array_pattern_hole_and_rest() {
        final var pattern = assertInstanceOf(ArrayPattern.class, firstDeclarator("const [a, , ...r] = arr").getId());
        assertEquals(3, pattern.getElements().size());
        assertNull(pattern.getElements().get(1));
        final var rest = assertInstanceOf(RestElement.class, pattern.getElements().get(2));
        assertEquals("r", assertInstanceOf(Identifier.class, rest.getArgument()).getName());
    }

    @Test
    public void test_object_pattern_renamed_and_default() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a: x, b = 2} = o").getId());
        final var renamed = assertInstanceOf(Property.class, pattern.getProperties().getFirst());
        assertEquals("x", assertInstanceOf(Identifier.class, renamed.getValue()).getName());
        final var defaulted = assertInstanceOf(Property.class, pattern.getProperties().get(1));
        final var assignment = assertInstanceOf(AssignmentPattern.class, defaulted.getValue());
        assertEquals(2.0, assertInstanceOf(NumberLiteral.class, assignment.getRight()).getValue());
    }

    @Test
    public void test_object_pattern_rest() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a, ...r} = o").getId());
        final var rest = assertInstanceOf(RestElement.class, pattern.getProperties().get(1));
        assertEquals("r", assertInstanceOf(Identifier.class, rest.getArgument()).getName());
    }

    @Test
    public void test_nested_pattern() {
        final var pattern = assertInstanceOf(ObjectPattern.class, firstDeclarator("const {a: [b, {c}]} = o").getId());
        final var outer = assertInstanceOf(Property.class, pattern.getProperties().getFirst());
        final var inner = assertInstanceOf(ArrayPattern.class, outer.getValue());
        assertInstanceOf(Identifier.class, inner.getElements().getFirst());
        assertInstanceOf(ObjectPattern.class, inner.getElements().get(1));
    }

    @Test
    public void test_pattern_and_default_params() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function f(a = 1, {b}, [c]) {}"));
        assertEquals(3, fn.getParams().size());
        final var defaulted = assertInstanceOf(AssignmentPattern.class, fn.getParams().getFirst());
        assertEquals("a", assertInstanceOf(Identifier.class, defaulted.getLeft()).getName());
        assertInstanceOf(ObjectPattern.class, fn.getParams().get(1));
        assertInstanceOf(ArrayPattern.class, fn.getParams().get(2));
    }

    @Test
    public void test_arrow_pattern_params() {
        final var arrow = assertInstanceOf(ArrowFunctionExpression.class, firstExpression("({a}, [b]) => a"));
        assertInstanceOf(ObjectPattern.class, arrow.getParams().getFirst());
        assertInstanceOf(ArrayPattern.class, arrow.getParams().get(1));
    }

    @Test
    public void test_catch_pattern() {
        final var tryStatement = assertInstanceOf(TryStatement.class, firstStatement("try {} catch ({message}) {}"));
        final var handler = assertInstanceOf(CatchClause.class, tryStatement.getHandler());
        assertInstanceOf(ObjectPattern.class, handler.getParam());
    }

    @Test
    public void test_empty_patterns() {
        assertInstanceOf(ObjectPattern.class, firstDeclarator("const {} = o").getId());
        assertInstanceOf(ArrayPattern.class, firstDeclarator("const [] = a").getId());
    }

    @Test
    public void test_from_as_are_contextual() {
        assertInstanceOf(VariableDeclaration.class, firstStatement("let from = 1;"));
        assertInstanceOf(VariableDeclaration.class, firstStatement("const as = 2;"));
    }
}
