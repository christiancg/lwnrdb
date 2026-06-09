package org.techhouse.ops.req.agg.step;

import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;

public class FilterAggregationStep extends BaseAggregationStep {
    private BaseOperator operator;

    public FilterAggregationStep(final BaseOperator operator) {
        super(AggregationStepType.FILTER);
        this.operator = operator;
    }

    public BaseOperator getOperator() {
        return operator;
    }

    public void setOperator(BaseOperator operator) {
        this.operator = operator;
    }
}
