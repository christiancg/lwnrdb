package org.techhouse.ops.req.agg.operators;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ops.req.agg.BaseOperator;
import org.techhouse.ops.req.agg.FieldOperatorType;
import org.techhouse.ops.req.agg.OperatorType;

@Getter
@Setter
public class FieldOperator extends BaseOperator {
    private FieldOperatorType fieldOperatorType;
    private String field;
    private JsonBaseElement value;
    public FieldOperator(FieldOperatorType operatorType, String field, JsonBaseElement value) {
        super(OperatorType.FIELD);
        this.fieldOperatorType = operatorType;
        this.field = field;
        this.value = value;
    }
}
