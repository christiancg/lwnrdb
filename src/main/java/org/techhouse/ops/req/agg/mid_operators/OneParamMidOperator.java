package org.techhouse.ops.req.agg.mid_operators;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OneParamMidOperator extends BaseMidOperator {
    private String operand;
    public OneParamMidOperator(MidOperationType type, String operand) {
        super(type);
        this.operand = operand;
    }
}
