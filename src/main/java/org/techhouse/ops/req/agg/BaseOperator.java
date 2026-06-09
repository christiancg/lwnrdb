package org.techhouse.ops.req.agg;

public class BaseOperator {
    private final OperatorType type;

    public BaseOperator(OperatorType type) {
        this.type = type;
    }

    public OperatorType getType() {
        return type;
    }
}
