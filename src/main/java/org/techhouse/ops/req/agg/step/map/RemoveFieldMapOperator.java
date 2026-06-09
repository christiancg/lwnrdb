package org.techhouse.ops.req.agg.step.map;

import org.techhouse.ops.req.agg.BaseOperator;

public class RemoveFieldMapOperator extends MapOperator {
    public RemoveFieldMapOperator(String fieldName, BaseOperator condition) {
        super(MapOperationType.REMOVE_FIELD, fieldName, condition);
    }
}
