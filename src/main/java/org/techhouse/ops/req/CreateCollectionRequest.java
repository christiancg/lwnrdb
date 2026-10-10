package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CreateCollectionRequest extends OperationRequest {
    public CreateCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.CREATE_COLLECTION, databaseName, collectionName);
    }
}
