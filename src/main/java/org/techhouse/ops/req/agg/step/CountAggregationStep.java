package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class CountAggregationStep extends BaseAggregationStep {
    public CountAggregationStep() {
        super(AggregationStepType.COUNT);
    }
}
