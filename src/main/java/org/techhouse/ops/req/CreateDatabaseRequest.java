package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CreateDatabaseRequest extends OperationRequest {
    public CreateDatabaseRequest(String databaseName) {
        super(OperationType.CREATE_DATABASE, databaseName, null);
    }
}
