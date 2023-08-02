package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class NotEqualsOperator extends BaseFieldOperator {
    public NotEqualsOperator(String field, JsonElement value) {
        super(FieldOperatorType.NOT_EQUALS, field, value);
    }
}
