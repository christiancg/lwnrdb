package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

import java.util.List;

public class BulkSaveResponse extends OperationResponse {
    public List<String> inserted;
    public List<String> updated;

    public BulkSaveResponse(OperationStatus status, String message, List<String> inserted, List<String> updated) {
        super(OperationType.BULK_SAVE, status, message);
        this.inserted = inserted;
        this.updated = updated;
    }

    public List<String> getInserted() {
        return inserted;
    }

    public void setInserted(List<String> inserted) {
        this.inserted = inserted;
    }

    public List<String> getUpdated() {
        return updated;
    }

    public void setUpdated(List<String> updated) {
        this.updated = updated;
    }
}
