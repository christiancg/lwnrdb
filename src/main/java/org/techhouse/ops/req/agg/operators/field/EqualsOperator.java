package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class EqualsOperator extends BaseFieldOperator {
    public EqualsOperator(String field, JsonElement value) {
        super(FieldOperatorType.EQUALS, field, value);
    }
}
