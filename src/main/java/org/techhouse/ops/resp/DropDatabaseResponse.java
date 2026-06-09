package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DropDatabaseResponse extends OperationResponse {
    public DropDatabaseResponse(OperationStatus status, String message) {
        super(OperationType.DROP_DATABASE, status, message);
    }
}
