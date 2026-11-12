package org.techhouse.ops.req;

import java.util.List;
import org.techhouse.ops.OperationType;

public class StartSnapshotRequest extends OperationRequest {
    private List<String> collectionNames;

    public StartSnapshotRequest(String databaseName) {
        super(OperationType.START_SNAPSHOT, databaseName, null);
    }

    public List<String> getCollectionNames() {
        return collectionNames;
    }

    public void setCollectionNames(List<String> collectionNames) {
        this.collectionNames = collectionNames;
    }
}
