package org.techhouse.ops.req.agg.operators.conjunction;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;

import java.util.List;

public class XorOperator extends BaseConjunctionOperator {
    public XorOperator(List<BaseOperator> operators) {
        super(ConjunctionOperatorType.XOR, operators);
    }
}
