package org.techhouse.unit.ops.req.agg.step;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;

public class SkipAggregationStepTest {
    @Test
    public void test_constructor_with_positive_skip() {
        Integer skipValue = 5;
        SkipAggregationStep skipStep = new SkipAggregationStep(skipValue);

        assertEquals(AggregationStepType.SKIP, skipStep.getType());
        assertEquals(skipValue, skipStep.getSkip());
    }

    @Test
    public void test_constructor_with_zero_skip() {
        Integer skipValue = 0;
        SkipAggregationStep skipStep = new SkipAggregationStep(skipValue);

        assertEquals(AggregationStepType.SKIP, skipStep.getType());
        assertEquals(skipValue, skipStep.getSkip());
    }

    @Test
    public void test_getters_and_setters() {
        Integer initialSkipValue = 10;
        SkipAggregationStep skipStep = new SkipAggregationStep(initialSkipValue);

        assertEquals(initialSkipValue, skipStep.getSkip());

        Integer newSkipValue = 20;
        skipStep.setSkip(newSkipValue);
        assertEquals(newSkipValue, skipStep.getSkip());
    }
}
