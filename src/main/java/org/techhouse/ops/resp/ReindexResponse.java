package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ReindexResponse extends OperationResponse {
    public List<String> rebuiltFields;

    public ReindexResponse(OperationStatus status, String message, List<String> rebuiltFields) {
        super(OperationType.REINDEX, status, message);
        this.rebuiltFields = rebuiltFields;
    }

    public List<String> getRebuiltFields() {
        return rebuiltFields;
    }

    public void setRebuiltFields(List<String> rebuiltFields) {
        this.rebuiltFields = rebuiltFields;
    }
}
