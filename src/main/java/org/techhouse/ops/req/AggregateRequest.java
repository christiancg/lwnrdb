package org.techhouse.ops.req;

import lombok.Getter;
import lombok.Setter;
import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

import java.util.List;

@Getter
@Setter
public class AggregateRequest extends OperationRequest {
    private List<BaseAggregationStep> aggregationSteps;
    public AggregateRequest(String databaseName, String collectionName) {
        super(OperationType.AGGREGATE, databaseName, collectionName);
    }
}
