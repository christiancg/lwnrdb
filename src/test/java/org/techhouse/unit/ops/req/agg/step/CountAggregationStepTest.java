package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.CountAggregationStep;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CountAggregationStepTest {
    // Constructor initializes with COUNT type
    @Test
    public void test_constructor_sets_count_type() {
        CountAggregationStep step = new CountAggregationStep();
    
        assertEquals(AggregationStepType.COUNT, step.getType());
    }
}