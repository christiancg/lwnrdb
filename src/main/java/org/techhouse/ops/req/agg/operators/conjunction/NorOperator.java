package org.techhouse.ops.req.agg.operators.conjunction;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;

import java.util.List;

public class NorOperator extends BaseConjunctionOperator {
    public NorOperator(List<BaseOperator> operators) {
        super(ConjunctionOperatorType.NOR, operators);
    }
}
