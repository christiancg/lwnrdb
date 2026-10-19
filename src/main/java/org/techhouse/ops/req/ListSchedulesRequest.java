package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ListSchedulesRequest extends OperationRequest {
    private boolean includeArgs;

    public ListSchedulesRequest() {
        super(OperationType.LIST_SCHEDULES, null, null);
    }

    public ListSchedulesRequest(String databaseName) {
        super(OperationType.LIST_SCHEDULES, databaseName, null);
    }

    public boolean isIncludeArgs() {
        return includeArgs;
    }

    public void setIncludeArgs(boolean includeArgs) {
        this.includeArgs = includeArgs;
    }
}
