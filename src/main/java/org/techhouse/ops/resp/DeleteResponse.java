package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteResponse extends OperationResponse {
    public DeleteResponse(OperationStatus status, String message) {
        super(OperationType.DELETE, status, message);
    }
}
