package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.internal.JsonSizeEstimator;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class StartSnapshotResponse extends OperationResponse {
    private final String snapshotId;
    private final List<String> collectionNames;

    public StartSnapshotResponse(String message, String snapshotId, List<String> collectionNames) {
        super(OperationType.START_SNAPSHOT, OperationStatus.OK, message);
        this.snapshotId = snapshotId;
        this.collectionNames = collectionNames;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public List<String> getCollectionNames() {
        return collectionNames;
    }

    @Override
    public int estimatedJsonChars() {
        return withPayloadChars(JsonSizeEstimator.estimateStrings(collectionNames) + snapshotId.length());
    }
}
