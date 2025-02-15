package org.techhouse.unit.ops.req.agg.step.map;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.step.map.MapOperationType;
import org.techhouse.ops.req.agg.step.map.RemoveFieldMapOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class RemoveFieldMapOperatorTest {
    // Constructor correctly sets REMOVE_FIELD as MapOperationType
    @Test
    public void test_constructor_sets_remove_field_type() {
        String fieldName = "testField";
        BaseOperator condition = new BaseOperator(OperatorType.FIELD);
    
        RemoveFieldMapOperator operator = new RemoveFieldMapOperator(fieldName, condition);
    
        assertEquals(MapOperationType.REMOVE_FIELD, operator.getType());
        assertEquals(fieldName, operator.getFieldName());
        assertEquals(condition, operator.getCondition());
    }

    // Constructor called with null fieldName parameter
    @Test
    public void test_constructor_with_null_field_name() {
        BaseOperator condition = new BaseOperator(OperatorType.FIELD);
    
        RemoveFieldMapOperator operator = new RemoveFieldMapOperator(null, condition);
    
        assertNull(operator.getFieldName());
        assertEquals(MapOperationType.REMOVE_FIELD, operator.getType());
        assertEquals(condition, operator.getCondition());
    }
}