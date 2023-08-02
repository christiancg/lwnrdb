package org.techhouse.ops.req.agg.operators.conjunction;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;

import java.util.List;

public class NandOperator extends BaseConjunctionOperator {
    public NandOperator(List<BaseOperator> operators) {
        super(ConjunctionOperatorType.NAND, operators);
    }
}
