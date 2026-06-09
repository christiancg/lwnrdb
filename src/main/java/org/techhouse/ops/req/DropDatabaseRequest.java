package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DropDatabaseRequest extends OperationRequest {
    public DropDatabaseRequest(String databaseName) {
        super(OperationType.DROP_DATABASE, databaseName, null);
    }
}
