package org.techhouse.ops.req.agg.mid_operators;

public class OneParamMidOperator extends BaseMidOperator {
    private String operand;

    public OneParamMidOperator(MidOperationType type, String operand) {
        super(type);
        this.operand = operand;
    }

    public String getOperand() {
        return operand;
    }

    public void setOperand(String operand) {
        this.operand = operand;
    }
}
