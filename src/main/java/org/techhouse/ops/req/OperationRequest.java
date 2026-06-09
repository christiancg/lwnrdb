package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class OperationRequest {
    private final OperationType type;
    private final String databaseName;
    private final String collectionName;

    public OperationRequest(OperationType type, String databaseName, String collectionName) {
        this.type = type;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    public OperationType getType() {
        return type;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getCollectionName() {
        return collectionName;
    }
}
