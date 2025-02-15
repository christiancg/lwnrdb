package org.techhouse.unit.ops.req.agg.step.map;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperationType;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class AddFieldMapOperatorTest {
    // Constructor creates instance with ADD_FIELD type, fieldName, condition and operator
    @Test
    public void test_constructor_creates_instance_with_valid_parameters() {
        String fieldName = "testField";
        BaseOperator condition = new BaseOperator(OperatorType.FIELD);
        BaseMidOperator operator = new BaseMidOperator(MidOperationType.SUM);

        AddFieldMapOperator addFieldMapOperator = new AddFieldMapOperator(fieldName, condition, operator);

        assertEquals(MapOperationType.ADD_FIELD, addFieldMapOperator.getType());
        assertEquals(fieldName, addFieldMapOperator.getFieldName());
        assertEquals(condition, addFieldMapOperator.getCondition());
        assertEquals(operator, addFieldMapOperator.getOperator());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String fieldName = "testField";
        BaseOperator condition = new BaseOperator(OperatorType.FIELD);
        BaseMidOperator operator = new BaseMidOperator(MidOperationType.SUM);

        AddFieldMapOperator addFieldMapOperator = new AddFieldMapOperator(fieldName, condition, operator);

        assertEquals(MapOperationType.ADD_FIELD, addFieldMapOperator.getType());
        assertEquals(fieldName, addFieldMapOperator.getFieldName());
        assertEquals(condition, addFieldMapOperator.getCondition());
        assertEquals(operator, addFieldMapOperator.getOperator());
    }
}