package org.techhouse.unit.simplejs.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.nodes.AssignmentExpression;
import org.techhouse.simplejs.nodes.BinaryExpression;
import org.techhouse.simplejs.nodes.BinaryOperator;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.nodes.NumberLiteral;
import org.techhouse.simplejs.nodes.UnaryExpression;
import org.techhouse.simplejs.nodes.UnaryOperator;
import org.techhouse.simplejs.nodes.UpdateExpression;

public class BinaryOperatorTest {
    private static final Identifier ID = new Identifier("x");
    private static final NumberLiteral ONE = new NumberLiteral(1);

    @Test
    public void test_every_token_resolves_to_its_operator() {
        for (final var operator : BinaryOperator.values()) {
            assertSame(operator, BinaryOperator.of(operator.token()));
        }
        for (final var operator : UnaryOperator.values()) {
            assertSame(operator, UnaryOperator.of(operator.token()));
        }
    }

    @Test
    public void test_an_unknown_token_throws() {
        assertThrows(TypeErrorException.class, () -> BinaryOperator.of("&&"));
        assertThrows(TypeErrorException.class, () -> UnaryOperator.of("++"));
        assertThrows(TypeErrorException.class, () -> BinaryOperator.compoundOf("<>="));
    }

    @Test
    public void test_a_compound_assignment_resolves_its_base_operator() {
        assertSame(BinaryOperator.ADD, BinaryOperator.compoundOf("+="));
        assertSame(BinaryOperator.UNSIGNED_SHIFT_RIGHT, BinaryOperator.compoundOf(">>>="));
        assertSame(BinaryOperator.EXPONENT, BinaryOperator.compoundOf("**="));
        assertNull(BinaryOperator.compoundOf("="));
        assertNull(BinaryOperator.compoundOf("&&="));
        assertNull(BinaryOperator.compoundOf("||="));
        assertNull(BinaryOperator.compoundOf("??="));
    }

    @Test
    public void test_nodes_resolve_their_operator_once_at_construction() {
        assertSame(BinaryOperator.STRICT_NOT_EQUAL, new BinaryExpression("!==", ID, ONE).kind());
        assertEquals("!==", new BinaryExpression("!==", ID, ONE).getOperator());
        assertSame(UnaryOperator.TYPEOF, new UnaryExpression("typeof", ID, true).kind());
        assertSame(BinaryOperator.REMAINDER, new AssignmentExpression("%=", ID, ONE).compoundKind());
        assertNull(new AssignmentExpression("=", ID, ONE).compoundKind());
        assertTrue(new UpdateExpression("++", ID, true).isIncrement());
        assertFalse(new UpdateExpression("--", ID, false).isIncrement());
    }
}
