package org.techhouse.unit.simplejs.nodes;

import java.util.List;
import org.junit.jupiter.api.Test;
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
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.ForStatement;
import org.techhouse.simplejs.nodes.FunctionDeclaration;
import org.techhouse.simplejs.nodes.FunctionExpression;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.IfStatement;
import org.techhouse.simplejs.nodes.JsNode.NodeType;
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
import org.techhouse.simplejs.nodes.StringLiteral;
import org.techhouse.simplejs.nodes.TemplateLiteral;
import org.techhouse.simplejs.nodes.ThisExpression;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UndefinedLiteral;
import org.techhouse.simplejs.nodes.UpdateExpression;
import org.techhouse.simplejs.nodes.VariableDeclaration;
import org.techhouse.simplejs.nodes.VariableDeclarator;
import org.techhouse.simplejs.nodes.WhileStatement;

import static org.junit.jupiter.api.Assertions.*;

public class JsNodeTest {
    // Each node subclass reports the matching NodeType
    @Test
    public void test_get_type_for_each_subclass() {
        final var id = new Identifier("x");
        final var num = new NumberLiteral(1.0);
        final var block = new BlockStatement(List.of());
        assertEquals(NodeType.PROGRAM, new Program(List.of()).getType());
        assertEquals(NodeType.VARIABLE_DECLARATION, new VariableDeclaration("let", List.of()).getType());
        assertEquals(NodeType.VARIABLE_DECLARATOR, new VariableDeclarator(id, null).getType());
        assertEquals(NodeType.BLOCK_STATEMENT, block.getType());
        assertEquals(NodeType.IF_STATEMENT, new IfStatement(id, new EmptyStatement(), null).getType());
        assertEquals(NodeType.WHILE_STATEMENT, new WhileStatement(id, new EmptyStatement()).getType());
        assertEquals(NodeType.FOR_STATEMENT, new ForStatement(null, null, null, new EmptyStatement()).getType());
        assertEquals(NodeType.RETURN_STATEMENT, new ReturnStatement(null).getType());
        assertEquals(NodeType.BREAK_STATEMENT, new BreakStatement().getType());
        assertEquals(NodeType.CONTINUE_STATEMENT, new ContinueStatement().getType());
        assertEquals(NodeType.EXPRESSION_STATEMENT, new ExpressionStatement(id).getType());
        assertEquals(NodeType.FUNCTION_DECLARATION,
                new FunctionDeclaration(new Identifier("f"), List.of(), block).getType());
        assertEquals(NodeType.EMPTY_STATEMENT, new EmptyStatement().getType());
        assertEquals(NodeType.NUMBER_LITERAL, num.getType());
        assertEquals(NodeType.STRING_LITERAL, new StringLiteral("s").getType());
        assertEquals(NodeType.BOOLEAN_LITERAL, new BooleanLiteral(true).getType());
        assertEquals(NodeType.NULL_LITERAL, new NullLiteral().getType());
        assertEquals(NodeType.UNDEFINED_LITERAL, new UndefinedLiteral().getType());
        assertEquals(NodeType.REGEX_LITERAL, new RegexLiteral("a", "g").getType());
        assertEquals(NodeType.TEMPLATE_LITERAL, new TemplateLiteral(List.of(""), List.of()).getType());
        assertEquals(NodeType.IDENTIFIER, id.getType());
        assertEquals(NodeType.THIS_EXPRESSION, new ThisExpression().getType());
        assertEquals(NodeType.ARRAY_EXPRESSION, new ArrayExpression(List.of()).getType());
        assertEquals(NodeType.OBJECT_EXPRESSION, new ObjectExpression(List.of()).getType());
        assertEquals(NodeType.PROPERTY, new Property(id, num, false, false).getType());
        assertEquals(NodeType.UNARY_EXPRESSION, new UnaryExpression("!", id, true).getType());
        assertEquals(NodeType.UPDATE_EXPRESSION, new UpdateExpression("++", id, true).getType());
        assertEquals(NodeType.BINARY_EXPRESSION, new BinaryExpression("+", id, num).getType());
        assertEquals(NodeType.LOGICAL_EXPRESSION, new LogicalExpression("&&", id, num).getType());
        assertEquals(NodeType.ASSIGNMENT_EXPRESSION, new AssignmentExpression("=", id, num).getType());
        assertEquals(NodeType.CONDITIONAL_EXPRESSION, new ConditionalExpression(id, num, num).getType());
        assertEquals(NodeType.CALL_EXPRESSION, new CallExpression(id, List.of()).getType());
        assertEquals(NodeType.MEMBER_EXPRESSION, new MemberExpression(id, id, false, false).getType());
        assertEquals(NodeType.NEW_EXPRESSION, new NewExpression(id, List.of()).getType());
        assertEquals(NodeType.FUNCTION_EXPRESSION, new FunctionExpression(null, List.of(), block).getType());
        assertEquals(NodeType.ARROW_FUNCTION_EXPRESSION, new ArrowFunctionExpression(List.of(), num, true).getType());
    }

    // Node getters expose the values passed to the constructor
    @Test
    public void test_node_getters() {
        final var id = new Identifier("name");
        assertEquals("name", id.getName());
        final var member = new MemberExpression(id, new Identifier("p"), true, true);
        assertEquals(id, member.getObject());
        assertTrue(member.isComputed());
        assertTrue(member.isOptional());
        final var prop = new Property(id, id, false, true);
        assertTrue(prop.isShorthand());
        assertFalse(prop.isComputed());
        final var arrow = new ArrowFunctionExpression(List.of(id), new NumberLiteral(1.0), true);
        assertTrue(arrow.isExpressionBody());
        assertEquals(1, arrow.getParams().size());
        final var regex = new RegexLiteral("a", "gi");
        assertEquals("a", regex.getPattern());
        assertEquals("gi", regex.getFlags());
    }
}
