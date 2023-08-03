package org.techhouse.ops.req.agg.operators;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.ConjunctionOperatorType;
import org.techhouse.ops.req.agg.OperatorType;

import java.util.List;

@Getter
@Setter
public class ConjunctionOperator extends BaseOperator {
    private ConjunctionOperatorType conjunctionType;
    private List<BaseOperator> operators;
    public ConjunctionOperator(ConjunctionOperatorType conjunctionType, List<BaseOperator> operators) {
        super(OperatorType.CONJUNCTION);
        this.conjunctionType = conjunctionType;
        this.operators = operators;
    }
}
