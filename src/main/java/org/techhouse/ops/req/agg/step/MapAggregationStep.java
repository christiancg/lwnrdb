package org.techhouse.ops.req.agg.step;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.AggregationStepType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

@Getter
@Setter
public class MapAggregationStep extends BaseAggregationStep {
    public MapAggregationStep() {
        super(AggregationStepType.MAP);
    }
}
