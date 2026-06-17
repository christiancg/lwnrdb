package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class OperationRequest {
    private final OperationType type;
    private final String databaseName;
    private final String collectionName;
    // Opt-in dirty read: when true a read operation skips the collection-level read lock so it can
    // proceed even while a writer holds the collection. Physical per-file locks still guarantee that
    // every file read returns valid (never half-written) data. Defaults to false (fully locked read).
    private boolean dirtyRead;

    public OperationRequest(OperationType type, String databaseName, String collectionName) {
        this.type = type;
        this.databaseName = databaseName;
        this.collectionName = collectionName;
    }

    public OperationType getType() {
        return type;
    }

    public boolean isDirtyRead() {
        return dirtyRead;
    }

    public void setDirtyRead(boolean dirtyRead) {
        this.dirtyRead = dirtyRead;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getCollectionName() {
        return collectionName;
    }
}
