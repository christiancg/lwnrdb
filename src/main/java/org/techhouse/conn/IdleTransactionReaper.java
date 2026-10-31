package org.techhouse.conn;

import java.util.UUID;
import java.util.function.Supplier;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.data.Client;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.TransactionOperationHelper;

final class IdleTransactionReaper {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Logger logger = Logger.logFor(MessageProcessor.class);
    private final UUID clientId;
    private final Supplier<String> remoteAddress;
    private UUID unreapableTransaction;

    record ReadBudget(long millis, Client.TransactionReap reapReason) {
    }

    IdleTransactionReaper(UUID clientId, Supplier<String> remoteAddress) {
        this.clientId = clientId;
        this.remoteAddress = remoteAddress;
    }

    ReadBudget nextReadBudget() {
        if (lacksTimedTransaction()) {
            return new ReadBudget(0L, Client.TransactionReap.IDLE);
        }
        final var ageRemaining = ageRemainingMillis();
        final var idleTimeout = configuration.getTransactionIdleTimeoutMs();
        return new ReadBudget(Math.clamp(ageRemaining, 1L, idleTimeout),
                ageRemaining < idleTimeout ? Client.TransactionReap.MAX_AGE : Client.TransactionReap.IDLE);
    }

    long ageRemainingMillis() {
        final var maxAge = configuration.longValue(ConfigKey.TRANSACTION_MAX_AGE_MS);
        if (maxAge == 0 || lacksTimedTransaction()) {
            return Long.MAX_VALUE;
        }
        return maxAge - clientTracker.getActiveTransaction(clientId).ageMillis();
    }

    void reap(Client.TransactionReap reason) {
        if (lacksTimedTransaction()) {
            return;
        }
        final var transactionId = clientTracker.getActiveTransaction(clientId).getTransactionId();
        if (TransactionOperationHelper.reapIdleTransaction(clientId)) {
            clientTracker.markTransactionReaped(clientId, reason);
            logger.info("Rolled back transaction " + transactionId + " of " + remoteAddress.get() + " after "
                    + (reason == Client.TransactionReap.MAX_AGE
                            ? "reaching transactionMaxAgeMs"
                            : configuration.getTransactionIdleTimeoutMs() + " ms without a request"));
        } else {
            unreapableTransaction = transactionId;
            logger.warning("Could not roll back idle transaction " + transactionId + " of " + remoteAddress.get()
                    + "; it stays open and is no longer timed");
        }
    }

    private boolean lacksTimedTransaction() {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        return transaction == null || transaction.getTransactionId().equals(unreapableTransaction)
                || !TransactionOperationHelper.isReapable(clientId);
    }
}
