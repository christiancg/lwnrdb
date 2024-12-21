package org.techhouse.unit.ops.req.agg.mid_operators;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BaseMidOperatorTest {
    // Constructor correctly initializes MidOperationType field
    @Test
    public void test_constructor_initializes_type_field() {
        MidOperationType expectedType = MidOperationType.AVG;
        BaseMidOperator operator = new BaseMidOperator(expectedType);
    
        assertEquals(expectedType, operator.getType());
    }
}