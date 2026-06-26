package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteResponse extends OperationResponse {
    public DeleteResponse(String message) {
        super(OperationType.DELETE, OperationStatus.OK, message);
    }
}
