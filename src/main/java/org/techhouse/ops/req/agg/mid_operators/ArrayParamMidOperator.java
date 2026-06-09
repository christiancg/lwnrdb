package org.techhouse.ops.req.agg.mid_operators;

import org.techhouse.ejson.elements.JsonArray;

public class ArrayParamMidOperator extends BaseMidOperator {
    private JsonArray operands;

    public ArrayParamMidOperator(MidOperationType type, JsonArray operands) {
        super(type);
        this.operands = operands;
    }

    public JsonArray getOperands() {
        return operands;
    }

    public void setOperands(JsonArray operands) {
        this.operands = operands;
    }
}
