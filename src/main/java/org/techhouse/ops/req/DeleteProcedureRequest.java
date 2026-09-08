package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DeleteProcedureRequest extends OperationRequest {
    private String name;

    public DeleteProcedureRequest() {
        super(OperationType.DELETE_PROCEDURE, null, null);
    }

    public DeleteProcedureRequest(String databaseName, String name) {
        super(OperationType.DELETE_PROCEDURE, databaseName, null);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
