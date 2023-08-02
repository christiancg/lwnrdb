package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;

@Getter
@Setter
public class FilterAggregationStep extends BaseAggregationStep {
    private BaseOperator operator;
    public FilterAggregationStep(final BaseOperator operator) {
        super(AggregationStepType.FILTER);
        this.operator = operator;
    }
}
