package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ResolveTriggerRunResponse extends OperationResponse {
    private final boolean resolved;

    public ResolveTriggerRunResponse(String message, boolean resolved) {
        super(OperationType.RESOLVE_TRIGGER_RUN, OperationStatus.OK, message);
        this.resolved = resolved;
    }

    public boolean isResolved() {
        return resolved;
    }
}
