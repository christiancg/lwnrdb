package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class SmallerThanOperator extends BaseFieldOperator {
    public SmallerThanOperator(String field, JsonElement value) {
        super(FieldOperatorType.SMALLER_THAN, field, value);
    }
}
