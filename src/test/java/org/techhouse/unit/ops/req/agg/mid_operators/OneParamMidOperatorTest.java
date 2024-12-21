package org.techhouse.unit.ops.req.agg.mid_operators;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.mid_operators.OneParamMidOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class OneParamMidOperatorTest {
    // Create operator with valid type and operand
    @Test
    public void test_create_operator_with_valid_params() {
        MidOperationType type = MidOperationType.ABS;
        String operand = "field1";
    
        OneParamMidOperator operator = new OneParamMidOperator(type, operand);
    
        assertEquals(type, operator.getType());
        assertEquals(operand, operator.getOperand());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        MidOperationType type = MidOperationType.ABS;
        String operand = "field1";

        OneParamMidOperator operator = new OneParamMidOperator(type, operand);

        assertEquals(type, operator.getType());
        assertEquals(operand, operator.getOperand());

        String newOperand = "field2";
        operator.setOperand(newOperand);
        assertEquals(newOperand, operator.getOperand());
    }
}