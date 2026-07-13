package org.techhouse.ops.resp;

import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ops.OperationStatus;
import org.techhouse.ops.OperationType;

public class ListTransactionsResponse extends OperationResponse {
    private final List<JsonObject> transactions;

    public ListTransactionsResponse(String message, List<JsonObject> transactions) {
        super(OperationType.LIST_TRANSACTIONS, OperationStatus.OK, message);
        this.transactions = transactions;
    }

    public List<JsonObject> getTransactions() {
        return transactions;
    }
}
