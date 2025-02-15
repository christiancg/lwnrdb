package org.techhouse.unit.ops.req.agg.step.map;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;
import org.techhouse.ops.req.agg.step.map.MapOperationType;
import org.techhouse.ops.req.agg.step.map.MapOperator;

import static org.junit.jupiter.api.Assertions.*;

public class MapOperatorTest {
    // Create MapOperator with ADD_FIELD type and valid fieldName
    @Test
    public void create_map_operator_with_add_field_type() {
        String fieldName = "testField";
        MapOperator mapOperator = new MapOperator(MapOperationType.ADD_FIELD, fieldName, null);

        assertEquals(MapOperationType.ADD_FIELD, mapOperator.getType());
        assertEquals(fieldName, mapOperator.getFieldName());
        assertNull(mapOperator.getCondition());
    }

    // Test getters and setters provided by lombok
    @Test
    public void test_map_operator_getters_and_setters() {
        String fieldName = "testField";
        BaseOperator condition = new BaseOperator(OperatorType.FIELD);
        MapOperator mapOperator = new MapOperator(MapOperationType.ADD_FIELD, fieldName, condition);

        assertEquals(MapOperationType.ADD_FIELD, mapOperator.getType());
        assertEquals(fieldName, mapOperator.getFieldName());
        assertEquals(condition, mapOperator.getCondition());
    }
}