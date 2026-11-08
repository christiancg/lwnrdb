package org.techhouse.conn;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.techhouse.config.ConfigKey;
import org.techhouse.config.Configuration;
import org.techhouse.config.Globals;
import org.techhouse.data.Client;
import org.techhouse.ioc.IocContainer;
import org.techhouse.log.Logger;
import org.techhouse.ops.TransactionOperationHelper;

final class IdleTransactionReaper {
    private final ClientTracker clientTracker = IocContainer.get(ClientTracker.class);
    private final InFlightRequests inFlightRequests = IocContainer.get(InFlightRequests.class);
    private final Configuration configuration = Configuration.getInstance();
    private final Logger logger = Logger.logFor(MessageProcessor.class);
    private final UUID clientId;
    private final Supplier<String> remoteAddress;
    private final BooleanSupplier shuttingDown;
    private UUID unreapableTransaction;
    private long redriveDelayMillis = Globals.FENCE_REDRIVE_INITIAL_MS;

    record ReadBudget(long millis, Client.TransactionReap reapReason, boolean redrive) {
        ReadBudget(long millis, Client.TransactionReap reapReason) {
            this(millis, reapReason, false);
        }
    }

    IdleTransactionReaper(UUID clientId, Supplier<String> remoteAddress, BooleanSupplier shuttingDown) {
        this.clientId = clientId;
        this.remoteAddress = remoteAddress;
        this.shuttingDown = shuttingDown;
    }

    ReadBudget nextReadBudget() {
        if (TransactionOperationHelper.holdsRedrivableCommit(clientId)) {
            return new ReadBudget(redriveDelayMillis, Client.TransactionReap.IDLE, true);
        }
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

    boolean redrive() {
        inFlightRequests.enter();
        try {
            return !shuttingDown.getAsBoolean() && redriveOnce();
        } finally {
            inFlightRequests.exit();
        }
    }

    private boolean redriveOnce() {
        if (TransactionOperationHelper.redriveFenced(clientId)) {
            redriveDelayMillis = Globals.FENCE_REDRIVE_INITIAL_MS;
            return true;
        }
        redriveDelayMillis = Math.min(redriveDelayMillis * 2, Globals.FENCE_REDRIVE_MAX_MS);
        logger.warning("Transaction of " + remoteAddress.get() + " still cannot finish applying its commit; retrying"
                + " in " + redriveDelayMillis + " ms");
        return false;
    }

    @SuppressWarnings("BusyWait")
    void redriveUntilFinished() {
        while (TransactionOperationHelper.holdsRedrivableCommit(clientId) && !shuttingDown.getAsBoolean()
                && !redrive()) {
            try {
                Thread.sleep(redriveDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean lacksTimedTransaction() {
        final var transaction = clientTracker.getActiveTransaction(clientId);
        return transaction == null || transaction.getTransactionId().equals(unreapableTransaction)
                || !TransactionOperationHelper.isReapable(clientId);
    }
}
