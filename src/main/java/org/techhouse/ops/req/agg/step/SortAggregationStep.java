package org.techhouse.ops.req.agg.step;

import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class SortAggregationStep extends BaseAggregationStep {
    private String fieldName;
    private Boolean ascending;

    public SortAggregationStep(final String fieldName, final boolean ascending) {
        super(AggregationStepType.SORT);
        this.fieldName = fieldName;
        this.ascending = ascending;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public Boolean getAscending() {
        return ascending;
    }

    public void setAscending(Boolean ascending) {
        this.ascending = ascending;
    }
}
