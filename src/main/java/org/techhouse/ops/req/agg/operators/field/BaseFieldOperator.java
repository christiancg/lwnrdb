package org.techhouse.ops.req.agg.operators.field;

import com.google.gson.JsonElement;
import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.OperatorType;

@Getter
@Setter
public abstract class BaseFieldOperator extends BaseOperator {
    private FieldOperatorType fieldOperatorType;
    private String field;
    private JsonElement value;
    public BaseFieldOperator(FieldOperatorType operatorType, String field, JsonElement value) {
        super(OperatorType.FIELD);
        this.fieldOperatorType = operatorType;
        this.field = field;
        this.value = value;
    }
}
