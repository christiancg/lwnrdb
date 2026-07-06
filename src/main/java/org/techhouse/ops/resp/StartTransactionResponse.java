package org.techhouse.ops.resp;

import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class StartTransactionResponse extends OperationResponse {
    public String transactionId;

    public StartTransactionResponse(String message, String transactionId) {
        super(OperationType.START_TRANSACTION, OperationStatus.OK, message);
        this.transactionId = transactionId;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }
}
