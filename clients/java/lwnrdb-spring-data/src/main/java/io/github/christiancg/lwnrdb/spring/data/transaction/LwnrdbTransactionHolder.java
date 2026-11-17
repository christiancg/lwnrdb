package io.github.christiancg.lwnrdb.spring.data.transaction;

import io.github.christiancg.lwnrdb.client.Transaction;
import org.springframework.transaction.support.ResourceHolderSupport;

public class LwnrdbTransactionHolder extends ResourceHolderSupport {
    private final Transaction transaction;

    public LwnrdbTransactionHolder(Transaction transaction) {
        this.transaction = transaction;
    }

    public Transaction transaction() {
        return transaction;
    }
}
