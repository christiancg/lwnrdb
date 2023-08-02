package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.FieldOperatorType;

@Getter
@Setter
public class GreaterThanEqualsOperator extends BaseFieldOperator {
    public GreaterThanEqualsOperator(String field, JsonElement value) {
        super(FieldOperatorType.GREATER_THAN_EQUALS, field, value);
    }
}
