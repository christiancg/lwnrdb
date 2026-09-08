package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DeleteScheduleRequest extends OperationRequest {
    private String name;

    public DeleteScheduleRequest() {
        super(OperationType.DELETE_SCHEDULE, null, null);
    }

    public DeleteScheduleRequest(String databaseName, String name) {
        super(OperationType.DELETE_SCHEDULE, databaseName, null);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
