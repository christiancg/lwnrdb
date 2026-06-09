package org.techhouse.ops.req.agg.step.map;

import org.techhouse.ops.req.agg.BaseOperator;

public class MapOperator {
    private final MapOperationType type;
    private final String fieldName;
    private final BaseOperator condition;

    public MapOperator(MapOperationType type, String fieldName, BaseOperator condition) {
        this.type = type;
        this.fieldName = fieldName;
        this.condition = condition;
    }

    public MapOperationType getType() {
        return type;
    }

    public String getFieldName() {
        return fieldName;
    }

    public BaseOperator getCondition() {
        return condition;
    }
}
