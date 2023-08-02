package org.techhouse.ops.req.agg.operators.conjunction;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;

import java.util.List;

public class OrOperator extends BaseConjunctionOperator {
    public OrOperator(List<BaseOperator> operators) {
        super(ConjunctionOperatorType.OR, operators);
    }
}
