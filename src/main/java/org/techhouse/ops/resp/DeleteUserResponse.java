package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class DeleteUserResponse extends OperationResponse {
    public DeleteUserResponse(String message) {
        super(OperationType.DELETE_USER, OperationStatus.OK, message);
    }
}
