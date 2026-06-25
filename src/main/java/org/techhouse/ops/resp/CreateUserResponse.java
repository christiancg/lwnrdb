package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class CreateUserResponse extends OperationResponse {
    public CreateUserResponse(String message) {
        super(OperationType.CREATE_USER, OperationStatus.OK, message);
    }
}
