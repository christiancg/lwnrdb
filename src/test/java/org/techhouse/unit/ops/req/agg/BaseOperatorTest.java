package org.techhouse.unit.ops.req.agg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;

public class BaseOperatorTest {
    // Constructor creates BaseOperator instance with specified OperatorType
    @Test
    public void test_constructor_creates_base_operator_with_type() {
        BaseOperator operator = new BaseOperator(OperatorType.FIELD);

        assertEquals(OperatorType.FIELD, operator.getType());
    }
}
