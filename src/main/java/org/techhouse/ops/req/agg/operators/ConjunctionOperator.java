package org.techhouse.ops.req.agg.operators;

import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.OperatorType;

import java.util.List;

public class ConjunctionOperator extends BaseOperator {
    private ConjunctionOperatorType conjunctionType;
    private List<BaseOperator> operators;

    public ConjunctionOperator(ConjunctionOperatorType conjunctionType, List<BaseOperator> operators) {
        super(OperatorType.CONJUNCTION);
        this.conjunctionType = conjunctionType;
        this.operators = operators;
    }

    public ConjunctionOperatorType getConjunctionType() {
        return conjunctionType;
    }

    public void setConjunctionType(ConjunctionOperatorType conjunctionType) {
        this.conjunctionType = conjunctionType;
    }

    public List<BaseOperator> getOperators() {
        return operators;
    }

    public void setOperators(List<BaseOperator> operators) {
        this.operators = operators;
    }
}
