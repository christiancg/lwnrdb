package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class SmallerThanEqualsOperator extends BaseFieldOperator {
    public SmallerThanEqualsOperator(String field, JsonElement value) {
        super(FieldOperatorType.SMALLER_THAN_EQUALS, field, value);
    }
}
