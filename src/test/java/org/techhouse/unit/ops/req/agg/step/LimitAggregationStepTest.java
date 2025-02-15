package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.LimitAggregationStep;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class LimitAggregationStepTest {
    // Constructor initializes with positive limit value
    @Test
    public void test_constructor_with_positive_limit() {
        int expectedLimit = 10;
        LimitAggregationStep limitStep = new LimitAggregationStep(expectedLimit);

        assertEquals(expectedLimit, limitStep.getLimit());
        assertEquals(AggregationStepType.LIMIT, limitStep.getType());
    }

    // Constructor handles zero limit value
    @Test
    public void test_constructor_with_zero_limit() {
        int expectedLimit = 0;
        LimitAggregationStep limitStep = new LimitAggregationStep(expectedLimit);

        assertEquals(expectedLimit, limitStep.getLimit());
        assertEquals(AggregationStepType.LIMIT, limitStep.getType());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        int initialLimit = 5;
        LimitAggregationStep limitStep = new LimitAggregationStep(initialLimit);

        assertEquals(initialLimit, limitStep.getLimit());

        int newLimit = 15;
        limitStep.setLimit(newLimit);

        assertEquals(newLimit, limitStep.getLimit());
    }
}