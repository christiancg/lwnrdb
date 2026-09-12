package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class OperationRequest {
    private final OperationType type;
    private final String databaseName;
    private final String collectionName;
    private boolean dirtyRead;
    // Carried on the request rather than in a ThreadLocal so the bound survives a cluster forward, where a
    // thread-scoped counter would reset to zero on the receiving node and let a cascade run forever.
    private int triggerDepth;

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

    public int getTriggerDepth() {
        return triggerDepth;
    }

    public void setTriggerDepth(int triggerDepth) {
        this.triggerDepth = triggerDepth;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public String getCollectionName() {
        return collectionName;
    }
}
