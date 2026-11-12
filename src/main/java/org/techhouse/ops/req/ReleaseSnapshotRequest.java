package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class ReleaseSnapshotRequest extends OperationRequest {
    private String snapshotId;

    public ReleaseSnapshotRequest(String databaseName) {
        super(OperationType.RELEASE_SNAPSHOT, databaseName, null);
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public void setSnapshotId(String snapshotId) {
        this.snapshotId = snapshotId;
    }
}
