package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class GroupByAggregationStep extends BaseAggregationStep {
    private String fieldName;
    public GroupByAggregationStep(final String fieldName) {
        super(AggregationStepType.GROUP_BY);
        this.fieldName = fieldName;
    }
}
