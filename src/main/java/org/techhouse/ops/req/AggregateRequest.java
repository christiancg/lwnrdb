package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

public class AggregateRequest extends OperationRequest {
    private List<BaseAggregationStep> aggregationSteps;

    public AggregateRequest(String databaseName, String collectionName) {
        super(OperationType.AGGREGATE, databaseName, collectionName);
    }

    public List<BaseAggregationStep> getAggregationSteps() {
        return aggregationSteps;
    }

    public void setAggregationSteps(List<BaseAggregationStep> aggregationSteps) {
        this.aggregationSteps = aggregationSteps;
    }
}
