package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class CancelScriptResponse extends OperationResponse {
    private final boolean cancelled;

    public CancelScriptResponse(String message, boolean cancelled) {
        super(OperationType.CANCEL_SCRIPT, OperationStatus.OK, message);
        this.cancelled = cancelled;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
