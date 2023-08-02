package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class SortAggregationStep extends BaseAggregationStep {
    private String fieldName;
    private boolean ascending;
    public SortAggregationStep(final String fieldName, final boolean ascending) {
        super(AggregationStepType.SORT);
        this.fieldName = fieldName;
        this.ascending = ascending;
    }
}
