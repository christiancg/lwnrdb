package io.github.christiancg.lwnrdb.spring.data.transaction;

import io.github.christiancg.lwnrdb.client.LwnrdbClient;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.protocol.ErrorCode;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class LwnrdbTransactionManager extends AbstractPlatformTransactionManager implements ResourceTransactionManager {
    @Serial
    private static final long serialVersionUID = 1L;

    private final transient LwnrdbClient client;

    public LwnrdbTransactionManager(LwnrdbClient client) {
        this.client = Objects.requireNonNull(client, "client");
        setNestedTransactionAllowed(false);
    }

    @Override
    public Object getResourceFactory() {
        return client;
    }

    @Override
    protected Object doGetTransaction() {
        return new TransactionObject((LwnrdbTransactionHolder) TransactionSynchronizationManager.getResource(client));
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
        return ((TransactionObject) transaction).holder != null;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        final LwnrdbTransactionHolder holder;
        try {
            holder = new LwnrdbTransactionHolder(client.beginTransaction());
        } catch (LwnrdbException e) {
            throw new CannotCreateTransactionException("could not start an LWNRDB transaction", e);
        }
        holder.setSynchronizedWithTransaction(true);
        ((TransactionObject) transaction).holder = holder;
        TransactionSynchronizationManager.bindResource(client, holder);
    }

    @Override
    protected Object doSuspend(Object transaction) {
        ((TransactionObject) transaction).holder = null;
        return TransactionSynchronizationManager.unbindResource(client);
    }

    @Override
    protected void doResume(@Nullable Object transaction, Object suspendedResources) {
        TransactionSynchronizationManager.bindResource(client, suspendedResources);
    }

    @Override
    @SuppressWarnings("resource")
    protected void doCommit(DefaultTransactionStatus status) {
        try {
            holderOf(status).transaction().commit();
        } catch (LwnrdbException e) {
            throw new TransactionSystemException("could not commit the LWNRDB transaction", e);
        }
    }

    @Override
    @SuppressWarnings("resource")
    protected void doRollback(DefaultTransactionStatus status) {
        try {
            holderOf(status).transaction().rollback();
        } catch (LwnrdbException e) {
            if (!e.hasCode(ErrorCode.NO_ACTIVE_TRANSACTION)) {
                throw new TransactionSystemException("could not roll back the LWNRDB transaction", e);
            }
        }
    }

    @Override
    protected void doSetRollbackOnly(DefaultTransactionStatus status) {
        holderOf(status).setRollbackOnly();
    }

    @Override
    @SuppressWarnings("resource")
    protected void doCleanupAfterCompletion(Object transaction) {
        final var holder = ((TransactionObject) transaction).holder;
        TransactionSynchronizationManager.unbindResourceIfPossible(client);
        if (holder != null && holder.transaction().isOpen()) {
            try {
                holder.transaction().close();
            } catch (LwnrdbException ignored) {
            }
        }
    }

    private static LwnrdbTransactionHolder holderOf(DefaultTransactionStatus status) {
        return Objects.requireNonNull(((TransactionObject) status.getTransaction()).holder, "no LWNRDB transaction");
    }

    private static final class TransactionObject {
        private @Nullable LwnrdbTransactionHolder holder;

        TransactionObject(@Nullable LwnrdbTransactionHolder holder) {
            this.holder = holder;
        }
    }
}
