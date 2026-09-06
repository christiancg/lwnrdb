package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DeleteTriggerRequest extends OperationRequest {
    private String name;

    public DeleteTriggerRequest() {
        super(OperationType.DELETE_TRIGGER, null, null);
    }

    public DeleteTriggerRequest(String databaseName, String collectionName, String name) {
        super(OperationType.DELETE_TRIGGER, databaseName, collectionName);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
