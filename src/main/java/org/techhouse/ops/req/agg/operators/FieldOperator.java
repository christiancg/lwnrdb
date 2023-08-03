package org.techhouse.ops.req.agg.operators;

import com.google.gson.JsonElement;
import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.OperatorType;

@Getter
@Setter
public class FieldOperator extends BaseOperator {
    private FieldOperatorType fieldOperatorType;
    private String field;
    private JsonElement value;
    public FieldOperator(FieldOperatorType operatorType, String field, JsonElement value) {
        super(OperatorType.FIELD);
        this.fieldOperatorType = operatorType;
        this.field = field;
        this.value = value;
    }
}
