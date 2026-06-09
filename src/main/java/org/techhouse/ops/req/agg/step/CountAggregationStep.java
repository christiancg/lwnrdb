package org.techhouse.ops.req.agg.step;

import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class CountAggregationStep extends BaseAggregationStep {
    public CountAggregationStep() {
        super(AggregationStepType.COUNT);
    }
}
