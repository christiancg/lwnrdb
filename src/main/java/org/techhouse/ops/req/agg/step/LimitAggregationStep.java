package org.techhouse.ops.req.agg.step;

import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class LimitAggregationStep extends BaseAggregationStep {
    private Integer limit;

    public LimitAggregationStep(final int limit) {
        super(AggregationStepType.LIMIT);
        this.limit = limit;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }
}
