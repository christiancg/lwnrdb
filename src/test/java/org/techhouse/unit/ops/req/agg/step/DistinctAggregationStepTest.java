package org.techhouse.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.DistinctAggregationStep;

public class DistinctAggregationStepTest {
    @Test
    public void test_constructor_sets_distinct_type() {
        String fieldName = "testField";
        DistinctAggregationStep step = new DistinctAggregationStep(fieldName);

        assertEquals(AggregationStepType.DISTINCT, step.getType());
    }

    @Test
    public void test_constructor_accepts_null_field_name() {
        DistinctAggregationStep step = new DistinctAggregationStep(null);

        assertNull(step.getFieldName());
        assertEquals(AggregationStepType.DISTINCT, step.getType());
    }

    @Test
    public void test_getters_and_setters() {
        String initialFieldName = "initialField";
        DistinctAggregationStep step = new DistinctAggregationStep(initialFieldName);

        assertEquals(initialFieldName, step.getFieldName());

        String newFieldName = "newField";
        step.setFieldName(newFieldName);
        assertEquals(newFieldName, step.getFieldName());
    }
}
