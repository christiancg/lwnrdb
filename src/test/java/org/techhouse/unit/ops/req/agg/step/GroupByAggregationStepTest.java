package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class GroupByAggregationStepTest {
    // Constructor initializes with valid fieldName and sets GROUP_BY type
    @Test
    public void constructor_with_valid_field_name_sets_properties() {
        String fieldName = "testField";
    
        GroupByAggregationStep step = new GroupByAggregationStep(fieldName);
    
        assertEquals(AggregationStepType.GROUP_BY, step.getType());
        assertEquals(fieldName, step.getFieldName());
    }

    // Constructor handles empty string fieldName
    @Test
    public void constructor_with_empty_field_name_sets_empty_string() {
        String emptyFieldName = "";
    
        GroupByAggregationStep step = new GroupByAggregationStep(emptyFieldName);
    
        assertEquals(AggregationStepType.GROUP_BY, step.getType());
        assertEquals(emptyFieldName, step.getFieldName());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String fieldName = "testField";
        GroupByAggregationStep step = new GroupByAggregationStep(fieldName);

        // Test getter
        assertEquals("testField", step.getFieldName());

        // Test setter
        step.setFieldName("newField");
        assertEquals("newField", step.getFieldName());
    }
}