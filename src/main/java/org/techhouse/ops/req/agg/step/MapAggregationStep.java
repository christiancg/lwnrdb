package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.step.map.MapOperator;

import java.util.List;

@Getter
@Setter
public class MapAggregationStep extends BaseAggregationStep {
    private List<MapOperator> operators;
    public MapAggregationStep(List<MapOperator> operators) {
        super(AggregationStepType.MAP);
        this.operators = operators;
    }
}
