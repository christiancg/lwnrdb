package org.techhouse.ops.req.agg.mid_operators;

import com.google.gson.JsonArray;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArrayParamMidOperator extends BaseMidOperator {
    private JsonArray operands;
    public ArrayParamMidOperator(MidOperationType type, JsonArray operands) {
        super(type);
        this.operands = operands;
    }
}
