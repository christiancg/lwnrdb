package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class InOperator extends BaseFieldOperator {
    public InOperator(String field, JsonElement value) {
        super(FieldOperatorType.IN, field, value);
    }
}
