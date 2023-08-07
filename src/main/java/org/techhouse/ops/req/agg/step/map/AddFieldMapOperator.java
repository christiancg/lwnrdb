package org.techhouse.ops.req.agg.step.map;

import lombok.Getter;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.mid_operators.BaseMidOperator;

@Getter
public class AddFieldMapOperator extends MapOperator {
    private final BaseMidOperator operator;
    public AddFieldMapOperator(String fieldName, BaseOperator condition, BaseMidOperator operator) {
        super(MapOperationType.ADD_FIELD, fieldName, condition);
        this.operator = operator;
    }
}
