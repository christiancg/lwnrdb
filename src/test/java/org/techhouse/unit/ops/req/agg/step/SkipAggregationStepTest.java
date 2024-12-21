package org.techhouse.unit.ops.req.agg.step;

import org.junit.jupiter.api.Test;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.step.SkipAggregationStep;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SkipAggregationStepTest {
    // Constructor initializes with valid positive skip value
    @Test
    public void test_constructor_with_positive_skip() {
        Integer skipValue = 5;
        SkipAggregationStep skipStep = new SkipAggregationStep(skipValue);

        assertEquals(AggregationStepType.SKIP, skipStep.getType());
        assertEquals(skipValue, skipStep.getSkip());
    }

    // Constructor handles zero skip value
    @Test
    public void test_constructor_with_zero_skip() {
        Integer skipValue = 0;
        SkipAggregationStep skipStep = new SkipAggregationStep(skipValue);

        assertEquals(AggregationStepType.SKIP, skipStep.getType());
        assertEquals(skipValue, skipStep.getSkip());
    }

    // test getters and setters provided by lombok
    @Test
    public void test_getters_and_setters() {
        Integer initialSkipValue = 10;
        SkipAggregationStep skipStep = new SkipAggregationStep(initialSkipValue);

        // Test getter
        assertEquals(initialSkipValue, skipStep.getSkip());

        // Test setter
        Integer newSkipValue = 20;
        skipStep.setSkip(newSkipValue);
        assertEquals(newSkipValue, skipStep.getSkip());
    }
}