package org.techhouse.ops.req;

import org.techhouse.ops.OperationType;

public class StartTransactionRequest extends OperationRequest {
    public StartTransactionRequest() {
        super(OperationType.START_TRANSACTION, null, null);
    }
}
