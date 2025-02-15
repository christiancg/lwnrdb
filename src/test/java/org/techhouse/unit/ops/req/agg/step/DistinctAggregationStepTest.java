package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class DistinctAggregationStepTest {
    // Constructor sets AggregationStepType to DISTINCT
    @Test
    public void test_constructor_sets_distinct_type() {
        String fieldName = "testField";
        DistinctAggregationStep step = new DistinctAggregationStep(fieldName);

        assertEquals(AggregationStepType.DISTINCT, step.getType());
    }

    // Constructor handles null fieldName parameter
    @Test
    public void test_constructor_accepts_null_field_name() {
        String fieldName = null;
        DistinctAggregationStep step = new DistinctAggregationStep(fieldName);

        assertNull(step.getFieldName());
        assertEquals(AggregationStepType.DISTINCT, step.getType());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        String initialFieldName = "initialField";
        DistinctAggregationStep step = new DistinctAggregationStep(initialFieldName);

        // Test getter
        assertEquals(initialFieldName, step.getFieldName());

        // Test setter
        String newFieldName = "newField";
        step.setFieldName(newFieldName);
        assertEquals(newFieldName, step.getFieldName());
    }
}