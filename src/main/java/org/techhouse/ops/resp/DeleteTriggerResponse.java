package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteTriggerResponse extends OperationResponse {
    public DeleteTriggerResponse(String message) {
        super(OperationType.DELETE_TRIGGER, OperationStatus.OK, message);
    }
}
