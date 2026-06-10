package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class AuthenticateResponse extends OperationResponse {
    public AuthenticateResponse(OperationStatus status, String message) {
        super(OperationType.AUTHENTICATE, status, message);
    }
}
