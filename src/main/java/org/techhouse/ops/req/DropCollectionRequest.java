package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class DropCollectionRequest extends OperationRequest {
    public DropCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.DROP_COLLECTION, databaseName, collectionName);
    }
}
