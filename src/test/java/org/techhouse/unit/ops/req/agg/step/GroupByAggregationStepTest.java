package org.techhouse.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.GroupByAggregationStep;

public class GroupByAggregationStepTest {
    @Test
    public void constructor_with_valid_field_name_sets_properties() {
        String fieldName = "testField";

        GroupByAggregationStep step = new GroupByAggregationStep(fieldName);

        assertEquals(AggregationStepType.GROUP_BY, step.getType());
        assertEquals(fieldName, step.getFieldName());
    }

    @Test
    public void constructor_with_empty_field_name_sets_empty_string() {
        String emptyFieldName = "";

        GroupByAggregationStep step = new GroupByAggregationStep(emptyFieldName);

        assertEquals(AggregationStepType.GROUP_BY, step.getType());
        assertEquals(emptyFieldName, step.getFieldName());
    }

    @Test
    public void test_getters_and_setters() {
        String fieldName = "testField";
        GroupByAggregationStep step = new GroupByAggregationStep(fieldName);

        assertEquals("testField", step.getFieldName());

        step.setFieldName("newField");
        assertEquals("newField", step.getFieldName());
    }
}
