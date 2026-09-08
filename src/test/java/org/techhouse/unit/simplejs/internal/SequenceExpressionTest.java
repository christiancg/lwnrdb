package org.techhouse.unit.simplejs.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.internal.Interpreter;
import org.techhouse.simplejs.internal.Lexer;
import org.techhouse.simplejs.internal.Parser;
import org.techhouse.simplejs.nodes.ExpressionStatement;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.JsNode;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.SequenceExpression;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;

public class SequenceExpressionTest {
    private static double num(String source) {
        return ((JsNumber) Interpreter.run(source)).getValue();
    }

    private static String str() {
        return ((JsString) Interpreter.run("let s = ''; (s += 'a', s += 'b', s += 'c'); s")).getValue();
    }

    private static JsNode firstExpression(String source) {
        final var program = Parser.parse(Lexer.lexWithPositions(source));
        return ((ExpressionStatement) program.getBody().getFirst()).getExpression();
    }

    // A comma list parses to a SequenceExpression holding every operand
    @Test
    public void test_parses_to_sequence_expression() {
        final var node = firstExpression("a, b, c;");
        final var sequence = assertInstanceOf(SequenceExpression.class, node);
        assertEquals(3, sequence.getExpressions().size());
        assertEquals(JsNode.NodeType.SEQUENCE_EXPRESSION, sequence.getType());
        assertInstanceOf(Identifier.class, sequence.getExpressions().getFirst());
    }

    // A single expression is not wrapped, so existing trees are unchanged
    @Test
    public void test_single_expression_is_unwrapped() {
        assertInstanceOf(NumberLiteral.class, firstExpression("1;"));
    }

    // The value is the last operand and every operand is evaluated in order
    @Test
    public void test_evaluation_order_and_result() {
        assertEquals(3, num("(1, 2, 3)"));
        assertEquals("abc", str());
        assertEquals(2, num("let n = 0; const f = () => (n++, n++, n); f()"));
    }

    // A sequence is allowed in a return statement
    @Test
    public void test_sequence_in_return() {
        assertEquals(2, num("function f() { return (1, 2) } f()"));
    }

    // The classic for update accepts a comma list
    @Test
    public void test_sequence_in_for_update() {
        assertEquals(3, num("let i = 0, j = 10; for (let k = 0; k < 3; k++, i++, j--) {} i"));
        assertEquals(7, num("let i = 0, j = 10; for (let k = 0; k < 3; k++, i++, j--) {} j"));
    }

    // A per-iteration let binding still works with a comma update
    @Test
    public void test_sequence_with_per_iteration_bindings() {
        assertEquals(3, num("const fns = []; let j = 0;"
                + " for (let i = 0; i < 3; i++, j++) { fns.push(() => i) } fns[0]() + fns[1]() + fns[2]() + j - 3"));
    }

    // Call arguments and array/object literals keep their own comma meaning
    @Test
    public void test_commas_elsewhere_are_unaffected() {
        assertEquals(2, num("function f(a, b) { return b } f(1, 2)"));
        assertEquals(2, num("[1, 2].length"));
        assertEquals(2, num("Object.keys({a: 1, b: 2}).length"));
        assertEquals(3, num("let a = 1, b = 3; b"));
    }

    // A sequence works inside a computed member and a parenthesized head
    @Test
    public void test_sequence_in_computed_member() {
        assertEquals(2, num("const a = [1, 2, 3]; a[(0, 1)]"));
    }

    // A sequence in a while test evaluates every operand
    @Test
    public void test_sequence_in_while_test() {
        assertEquals(3, num("let n = 0, guard = 0; while ((guard++, n < 3)) { n++ } n"));
    }
}
