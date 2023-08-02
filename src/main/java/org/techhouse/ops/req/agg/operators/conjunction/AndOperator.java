package org.techhouse.ops.req.agg.operators.conjunction;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;

import java.util.List;

public class AndOperator extends BaseConjunctionOperator {
    public AndOperator(List<BaseOperator> operators) {
        super(ConjunctionOperatorType.AND, operators);
    }
}
