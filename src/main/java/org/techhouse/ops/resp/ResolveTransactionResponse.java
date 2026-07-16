package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ResolveTransactionResponse extends OperationResponse {
    public ResolveTransactionResponse(String message) {
        super(OperationType.RESOLVE_TRANSACTION, OperationStatus.OK, message);
    }
}
