package org.techhouse.ops.req.agg.mid_operators;

public class CastMidOperator extends BaseMidOperator {
    private String fieldName;
    private CastToType toType;

    public CastMidOperator(String fieldName, CastToType toType) {
        super(MidOperationType.CAST);
        this.fieldName = fieldName;
        this.toType = toType;
    }

    public String getFieldName() {
        return fieldName;
    }

    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }

    public CastToType getToType() {
        return toType;
    }

    public void setToType(CastToType toType) {
        this.toType = toType;
    }
}
