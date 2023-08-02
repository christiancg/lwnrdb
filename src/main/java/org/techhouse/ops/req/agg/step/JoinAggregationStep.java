package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class JoinAggregationStep extends BaseAggregationStep {
    private String localField;
    private String remoteField;
    private String asField;
    public JoinAggregationStep(final String localField, final String remoteField, final String asField) {
        super(AggregationStepType.JOIN);
        this.localField = localField;
        this.remoteField = remoteField;
        this.asField = asField;
    }
}
