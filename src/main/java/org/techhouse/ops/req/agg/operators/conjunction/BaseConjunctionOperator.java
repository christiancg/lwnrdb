package org.techhouse.ops.req.agg.operators.conjunction;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.OperatorType;

import java.util.List;

@Getter
@Setter
public abstract class BaseConjunctionOperator extends BaseOperator {
    private ConjunctionOperatorType conjunctionType;
    private List<BaseOperator> operators;
    public BaseConjunctionOperator(ConjunctionOperatorType conjunctionType, List<BaseOperator> operators) {
        super(OperatorType.CONJUNCTION);
        this.conjunctionType = conjunctionType;
        this.operators = operators;
    }
}
