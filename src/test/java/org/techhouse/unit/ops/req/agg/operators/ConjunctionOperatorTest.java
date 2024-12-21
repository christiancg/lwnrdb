package org.techhouse.unit.ops.req.agg.operators;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.operators.ConjunctionOperator;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ConjunctionOperatorTest {
    // Create ConjunctionOperator with valid conjunctionType AND and non-empty operators list
    @Test
    public void test_create_conjunction_operator_with_valid_type_and_operators() {
        List<BaseOperator> operators = Arrays.asList(
            new BaseOperator(OperatorType.FIELD),
            new BaseOperator(OperatorType.FIELD)
        );

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.AND, operators);

        assertEquals(ConjunctionOperatorType.AND, conjunctionOperator.getConjunctionType());
        assertEquals(operators, conjunctionOperator.getOperators());
        assertEquals(OperatorType.CONJUNCTION, conjunctionOperator.getType());
    }

    // Create ConjunctionOperator with null conjunctionType
    @Test
    public void test_create_conjunction_operator_with_null_type() {
        List<BaseOperator> operators = List.of(new BaseOperator(OperatorType.FIELD));

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(null, operators);

        assertNull(conjunctionOperator.getConjunctionType());
        assertEquals(operators, conjunctionOperator.getOperators());
        assertEquals(OperatorType.CONJUNCTION, conjunctionOperator.getType());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        List<BaseOperator> operators = Arrays.asList(
                new BaseOperator(OperatorType.FIELD),
                new BaseOperator(OperatorType.FIELD)
        );

        ConjunctionOperator conjunctionOperator = new ConjunctionOperator(ConjunctionOperatorType.OR, operators);

        // Test getters
        assertEquals(ConjunctionOperatorType.OR, conjunctionOperator.getConjunctionType());
        assertEquals(operators, conjunctionOperator.getOperators());

        // Test setters
        conjunctionOperator.setConjunctionType(ConjunctionOperatorType.AND);
        assertEquals(ConjunctionOperatorType.AND, conjunctionOperator.getConjunctionType());

        List<BaseOperator> newOperators = List.of(
                new BaseOperator(OperatorType.CONJUNCTION)
        );
        conjunctionOperator.setOperators(newOperators);
        assertEquals(newOperators, conjunctionOperator.getOperators());
    }
}