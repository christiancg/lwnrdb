package org.techhouse.ops.req.agg.step;

import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class SkipAggregationStep extends BaseAggregationStep {
    private Integer skip;

    public SkipAggregationStep(final Integer skip) {
        super(AggregationStepType.SKIP);
        this.skip = skip;
    }

    public Integer getSkip() {
        return skip;
    }

    public void setSkip(Integer skip) {
        this.skip = skip;
    }
}
