package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ListSchedulesRequest extends OperationRequest {
    public ListSchedulesRequest() {
        super(OperationType.LIST_SCHEDULES, null, null);
    }

    public ListSchedulesRequest(String databaseName) {
        super(OperationType.LIST_SCHEDULES, databaseName, null);
    }
}
