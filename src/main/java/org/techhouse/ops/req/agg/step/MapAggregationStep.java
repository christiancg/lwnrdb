package org.techhouse.ops.req.agg.step;

import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.map.MapOperator;

import java.util.List;

public class MapAggregationStep extends BaseAggregationStep {
    private List<MapOperator> operators;

    public MapAggregationStep(List<MapOperator> operators) {
        super(AggregationStepType.MAP);
        this.operators = operators;
    }

    public List<MapOperator> getOperators() {
        return operators;
    }

    public void setOperators(List<MapOperator> operators) {
        this.operators = operators;
    }
}
