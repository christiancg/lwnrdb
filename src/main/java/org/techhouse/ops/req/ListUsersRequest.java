package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;
import org.techhouse.ops.req.agg.BaseAggregationStep;

import java.util.ArrayList;
import java.util.List;

public class ListUsersRequest extends OperationRequest {
    private List<BaseAggregationStep> aggregationSteps;

    public ListUsersRequest() {
        super(OperationType.LIST_USERS, null, null);
        this.aggregationSteps = new ArrayList<>();
    }

    public List<BaseAggregationStep> getAggregationSteps() {
        return aggregationSteps != null ? aggregationSteps : new ArrayList<>();
    }

    public void setAggregationSteps(List<BaseAggregationStep> aggregationSteps) {
        this.aggregationSteps = aggregationSteps;
    }
}
