package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class GetSchemaRequest extends OperationRequest {
    public GetSchemaRequest(String databaseName, String collectionName) {
        super(OperationType.GET_SCHEMA, databaseName, collectionName);
    }
}
