package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class LimitAggregationStep extends BaseAggregationStep {
    private Integer limit;
    public LimitAggregationStep(final int limit) {
        super(AggregationStepType.LIMIT);
        this.limit = limit;
    }
}
