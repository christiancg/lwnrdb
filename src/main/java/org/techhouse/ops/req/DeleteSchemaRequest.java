package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DeleteSchemaRequest extends OperationRequest {
    public DeleteSchemaRequest(String databaseName, String collectionName) {
        super(OperationType.DELETE_SCHEMA, databaseName, collectionName);
    }
}
