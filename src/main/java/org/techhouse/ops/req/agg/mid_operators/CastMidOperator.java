package org.techhouse.ops.req.agg.mid_operators;

public class CastMidOperator extends BaseMidOperator {
    private String fieldName;
    private CastToType toType;
    private String customTypeName;

    public CastMidOperator(String fieldName, CastToType toType) {
        super(MidOperationType.CAST);
        this.fieldName = fieldName;
        this.toType = toType;
    }

    public CastMidOperator(String fieldName, String customTypeName) {
        super(MidOperationType.CAST);
        this.fieldName = fieldName;
        this.toType = CastToType.JSON_CUSTOM;
        this.customTypeName = customTypeName;
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

    public String getCustomTypeName() {
        return customTypeName;
    }

    public void setCustomTypeName(String customTypeName) {
        this.customTypeName = customTypeName;
    }
}
