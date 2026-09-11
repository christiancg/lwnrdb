package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ArrayExpression;
import org.techhouse.simplejs.nodes.ArrayPattern;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.AssignmentPattern;
import org.techhouse.simplejs.nodes.BigIntLiteral;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BooleanLiteral;
import org.techhouse.simplejs.nodes.CallExpression;
import org.techhouse.simplejs.nodes.Expression;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.MemberExpression;
import org.techhouse.simplejs.nodes.NewExpression;
import org.techhouse.simplejs.nodes.NullLiteral;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.ObjectExpression;
import org.techhouse.simplejs.nodes.ObjectPattern;
import org.techhouse.simplejs.nodes.Program;
import org.techhouse.simplejs.nodes.Property;
import org.techhouse.simplejs.nodes.RegexLiteral;
import org.techhouse.simplejs.nodes.SpreadElement;
import org.techhouse.simplejs.nodes.Statement;
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.TaggedTemplateExpression;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.TryStatement;
import org.techhouse.simplejs.nodes.UndefinedLiteral;
import org.techhouse.simplejs.nodes.YieldExpression;

public class ParserOperatorSyntaxTest {
    private static Program parse(String source) {
        return Parser.parse(Lexer.lex(source));
    }

    private static Statement firstStatement(String source) {
        return parse(source).getBody().getFirst();
    }

    private static Expression firstExpression(String source) {
        return ((ExpressionStatement) firstStatement(source)).getExpression();
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
    public void test_bigint_literal() {
        final var big = assertInstanceOf(BigIntLiteral.class, firstExpression("0xFFn"));
        assertEquals(new BigInteger("255"), big.getValue());
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
    public void test_instanceof_and_in_operators() {
        assertEquals("instanceof",
                assertInstanceOf(BinaryExpression.class, firstExpression("a instanceof B")).getOperator());
        assertEquals("in", assertInstanceOf(BinaryExpression.class, firstExpression("k in obj")).getOperator());
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
    public void test_optional_chaining() {
        final var opt = assertInstanceOf(MemberExpression.class, firstExpression("a?.b"));
        assertTrue(opt.isOptional());
        final var optComputed = assertInstanceOf(MemberExpression.class, firstExpression("a?.[b]"));
        assertTrue(optComputed.isOptional());
        assertTrue(optComputed.isComputed());
        final var optCall = assertInstanceOf(CallExpression.class, firstExpression("a?.(b)"));
        assertTrue(optCall.isOptional());
        assertFalse(assertInstanceOf(CallExpression.class, firstExpression("a(b)")).isOptional());
    }

    @Test
    public void test_chained_call_and_member() {
        final var call = assertInstanceOf(CallExpression.class, firstExpression("a.b().c(1)"));
        final var callee = assertInstanceOf(MemberExpression.class, call.getCallee());
        assertEquals("c", ((Identifier) callee.getProperty()).getName());
    }

    @Test
    public void test_new_with_tagged_template_callee() {
        final var neu = assertInstanceOf(NewExpression.class, firstExpression("new tag`x`()"));
        assertInstanceOf(TaggedTemplateExpression.class, neu.getCallee());
        assertTrue(neu.getArguments().isEmpty());
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

    // The same cover grammar IS legal - and reinterpreted into an ObjectPattern - when the whole
    // object literal sits on the left of a plain `=`.
    @Test
    public void test_object_cover_initialized_shorthand_as_assignment_target_parses() {
        assertInstanceOf(AssignmentExpression.class, firstExpression("({ a = 1 } = {})"));
    }

    @Test
    public void test_catch_optional_binding() {
        final var stmt = assertInstanceOf(TryStatement.class, firstStatement("try { x; } catch { y; }"));
        assertNull(stmt.getHandler().getParam());
    }

    @Test
    public void test_optional_semicolons() {
        assertEquals(2, parse("let x = 1\nx + 1").getBody().size());
        assertEquals(2, parse("let x = 1;\nx + 1;").getBody().size());
    }

    // yield appears at the assignment right-hand side
    @Test
    public void test_yield_as_assignment_rhs() {
        final var fn = assertInstanceOf(FunctionDeclaration.class, firstStatement("function* g() { x = yield 2; }"));
        final var stmt = assertInstanceOf(ExpressionStatement.class, fn.getBody().getBody().getFirst());
        final var assign = assertInstanceOf(AssignmentExpression.class, stmt.getExpression());
        assertInstanceOf(YieldExpression.class, assign.getValue());
    }

    // A spread element in an array literal wraps its argument
    @Test
    public void test_array_spread() {
        final var array = assertInstanceOf(ArrayExpression.class, firstExpression("[1, ...rest]"));
        assertEquals(2, array.getElements().size());
        final var spread = assertInstanceOf(SpreadElement.class, array.getElements().get(1));
        assertEquals("rest", assertInstanceOf(Identifier.class, spread.getArgument()).getName());
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
}
