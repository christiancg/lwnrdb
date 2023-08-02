package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class SkipAggregationStep extends BaseAggregationStep {
    private int skip;
    public SkipAggregationStep(final int skip) {
        super(AggregationStepType.SKIP);
        this.skip = skip;
    }
}
