package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class SetDatabaseOwnersResponse extends OperationResponse {
    public SetDatabaseOwnersResponse(String message) {
        super(OperationType.SET_DATABASE_OWNERS, OperationStatus.OK, message);
    }
}
