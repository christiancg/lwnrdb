package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class DistinctAggregationStep extends BaseAggregationStep {
    private String fieldName;
    public DistinctAggregationStep(final String fieldName) {
        super(AggregationStepType.DISTINCT);
        this.fieldName = fieldName;
    }
}
