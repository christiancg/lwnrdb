package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class AuthenticateResponse extends OperationResponse {
    public AuthenticateResponse(String message) {
        super(OperationType.AUTHENTICATE, OperationStatus.OK, message);
    }
}
