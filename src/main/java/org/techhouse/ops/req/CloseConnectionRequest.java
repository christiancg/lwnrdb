package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class CloseConnectionRequest extends OperationRequest {
    public CloseConnectionRequest() {
        super(OperationType.CLOSE_CONNECTION, null, null);
    }
}
