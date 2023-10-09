package org.techhouse.ops.req.agg.mid_operators;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ejson.JsonArray;

@Getter
@Setter
public class ArrayParamMidOperator extends BaseMidOperator {
    private JsonArray operands;
    public ArrayParamMidOperator(MidOperationType type, JsonArray operands) {
        super(type);
        this.operands = operands;
    }
}
