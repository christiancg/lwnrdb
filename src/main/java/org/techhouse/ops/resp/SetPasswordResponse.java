package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class SetPasswordResponse extends OperationResponse {
    public SetPasswordResponse(String message) {
        super(OperationType.SET_PASSWORD, OperationStatus.OK, message);
    }
}
