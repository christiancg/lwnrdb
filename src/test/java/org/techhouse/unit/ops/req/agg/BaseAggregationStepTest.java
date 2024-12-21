package org.techhouse.unit.ops.req.agg;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class BaseAggregationStepTest {
    // Create BaseAggregationStep instance with valid AggregationStepType
    @Test
    public void create_base_aggregation_step_with_valid_type() {
        AggregationStepType expectedType = AggregationStepType.FILTER;
    
        BaseAggregationStep step = new BaseAggregationStep(expectedType);
    
        assertEquals(expectedType, step.getType());
    }
}