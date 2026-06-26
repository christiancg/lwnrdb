package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class CreateDatabaseResponse extends OperationResponse {
    public CreateDatabaseResponse(String message) {
        super(OperationType.CREATE_DATABASE, OperationStatus.OK, message);
    }
}
