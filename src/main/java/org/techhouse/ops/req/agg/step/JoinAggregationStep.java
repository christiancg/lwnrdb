package org.techhouse.ops.req.agg.step;

import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class JoinAggregationStep extends BaseAggregationStep {
    private String joinCollection;
    private String localField;
    private String remoteField;
    private String asField;

    public JoinAggregationStep(final String joinCollection, final String localField,
                               final String remoteField, final String asField) {
        super(AggregationStepType.JOIN);
        this.joinCollection = joinCollection;
        this.localField = localField;
        this.remoteField = remoteField;
        this.asField = asField;
    }

    public String getJoinCollection() {
        return joinCollection;
    }

    public void setJoinCollection(String joinCollection) {
        this.joinCollection = joinCollection;
    }

    public String getLocalField() {
        return localField;
    }

    public void setLocalField(String localField) {
        this.localField = localField;
    }

    public String getRemoteField() {
        return remoteField;
    }

    public void setRemoteField(String remoteField) {
        this.remoteField = remoteField;
    }

    public String getAsField() {
        return asField;
    }

    public void setAsField(String asField) {
        this.asField = asField;
    }
}
