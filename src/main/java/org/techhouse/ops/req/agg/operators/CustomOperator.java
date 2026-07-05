package org.techhouse.ops.req.agg.operators;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.OperatorType;

// A type-specific filter operator (e.g. the geo type's "distance"/"within"), dispatched to the
// stored value's JsonCustom.applyCustomOperator. `value` holds the operand where one applies (e.g. the
// target geo of a distance query); `args` carries the operator's remaining parameters as raw JSON.
public class CustomOperator extends BaseOperator {
    private String field;
    private String customOperatorName;
    private JsonBaseElement value;
    private JsonObject args;

    public CustomOperator(String customOperatorName, String field, JsonBaseElement value, JsonObject args) {
        super(OperatorType.CUSTOM);
        this.customOperatorName = customOperatorName;
        this.field = field;
        this.value = value;
        this.args = args;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getCustomOperatorName() {
        return customOperatorName;
    }

    public void setCustomOperatorName(String customOperatorName) {
        this.customOperatorName = customOperatorName;
    }

    public JsonBaseElement getValue() {
        return value;
    }

    public void setValue(JsonBaseElement value) {
        this.value = value;
    }

    public JsonObject getArgs() {
        return args;
    }

    public void setArgs(JsonObject args) {
        this.args = args;
    }
}
