package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class NotInOperator extends BaseFieldOperator {
    public NotInOperator(String field, JsonElement value) {
        super(FieldOperatorType.NOT_IN, field, value);
    }
}
