package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import org.techhouse.ops.req.agg.FieldOperatorType;

public class ContainsOperator extends BaseFieldOperator {
    public ContainsOperator(String field, JsonElement value) {
        super(FieldOperatorType.CONTAINS, field, value);
    }
}
