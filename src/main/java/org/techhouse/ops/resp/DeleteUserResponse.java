package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteUserResponse extends OperationResponse {
    public DeleteUserResponse(OperationStatus status, String message) {
        super(OperationType.DELETE_USER, status, message);
    }
}
