package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class GreaterThanOperator extends BaseFieldOperator {
    public GreaterThanOperator(String field, JsonElement value) {
        super(FieldOperatorType.GREATER_THAN, field, value);
    }
}
