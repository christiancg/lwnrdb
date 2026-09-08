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
    // How deep a chain of trigger-fired writes this request is. 0 means a client asked for it directly.
    // Carried on the request rather than in a ThreadLocal so the bound survives a cluster forward, where a
    // thread-scoped counter would reset to zero on the receiving node and let a cascade run forever.
    // MessageProcessor zeroes it for every client-originated request, so a client cannot claim a value.
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
