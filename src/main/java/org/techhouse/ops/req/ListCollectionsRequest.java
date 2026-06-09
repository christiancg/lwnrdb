package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ListCollectionsRequest extends OperationRequest {
    public ListCollectionsRequest(String databaseName) {
        super(OperationType.LIST_COLLECTIONS, databaseName, null);
    }
}
