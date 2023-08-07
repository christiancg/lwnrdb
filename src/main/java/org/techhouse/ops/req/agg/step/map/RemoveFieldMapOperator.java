package org.techhouse.ops.req.agg.step.map;

import lombok.Getter;
import org.techhouse.ops.req.agg.BaseOperator;

@Getter
public class RemoveFieldMapOperator extends MapOperator {
    public RemoveFieldMapOperator(String fieldName, BaseOperator condition) {
        super(MapOperationType.REMOVE_FIELD, fieldName, condition);
    }
}
