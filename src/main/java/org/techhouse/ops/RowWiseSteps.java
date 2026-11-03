package org.techhouse.ops;

import org.techhouse.ops.req.agg.BaseAggregationStep;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.mid_operators.MidOperationType;
import org.techhouse.ops.req.agg.step.FilterAggregationStep;
import org.techhouse.ops.req.agg.step.MapAggregationStep;
import org.techhouse.ops.req.agg.step.map.AddFieldMapOperator;
import org.techhouse.ops.req.agg.step.map.MapOperator;

final class RowWiseSteps {
    private RowWiseSteps() {
    }

    static boolean isRowWise(BaseAggregationStep step) {
        return switch (step) {
            case FilterAggregationStep filter -> plainPredicate(filter.getOperator());
            case MapAggregationStep map -> map.getOperators().stream().allMatch(RowWiseSteps::plainMap);
            default -> false;
        };
    }

    private static boolean plainMap(MapOperator operator) {
        if (operator.getCondition() != null && !plainPredicate(operator.getCondition())) {
            return false;
        }
        return !(operator instanceof AddFieldMapOperator add) || add.getOperator() == null
                || add.getOperator().getType() != MidOperationType.SCRIPT;
    }

    private static boolean plainPredicate(BaseOperator operator) {
        return operator != null && !OperatorPredicate.holdsScript(operator)
                && !OperatorPredicate.holdsRanking(operator);
    }
}
