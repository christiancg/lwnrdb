package org.techhouse.unit.ops.req.agg.step.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperationType;

public class AddFieldMapOperatorTest {
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
