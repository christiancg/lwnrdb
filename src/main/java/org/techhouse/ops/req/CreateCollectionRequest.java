package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CreateCollectionRequest extends OperationRequest {
    private long incarnation;

    public CreateCollectionRequest(String databaseName, String collectionName) {
        super(OperationType.CREATE_COLLECTION, databaseName, collectionName);
    }

    public long getIncarnation() {
        return incarnation;
    }

    public void setIncarnation(long incarnation) {
        this.incarnation = incarnation;
    }
}
