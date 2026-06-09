package org.techhouse.ops.req.agg;

public class BaseAggregationStep {
    private final AggregationStepType type;

    public BaseAggregationStep(AggregationStepType type) {
        this.type = type;
    }

    public AggregationStepType getType() {
        return type;
    }
}
