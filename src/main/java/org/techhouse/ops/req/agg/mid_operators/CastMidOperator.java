package org.techhouse.ops.req.agg.mid_operators;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CastMidOperator extends BaseMidOperator {
    private String fieldName;
    private CastToType toType;
    public CastMidOperator(String fieldName, CastToType toType) {
        super(MidOperationType.CAST);
        this.fieldName = fieldName;
        this.toType = toType;
    }
}
