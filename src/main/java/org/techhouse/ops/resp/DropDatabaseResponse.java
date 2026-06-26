package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DropDatabaseResponse extends OperationResponse {
    public DropDatabaseResponse(String message) {
        super(OperationType.DROP_DATABASE, OperationStatus.OK, message);
    }
}
