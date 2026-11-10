package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.error.ConnectionLostException;
import io.github.christiancg.lwnrdb.client.error.LwnrdbException;
import io.github.christiancg.lwnrdb.client.error.UnknownOutcomeException;
import io.github.christiancg.lwnrdb.client.internal.ClientContext;
import io.github.christiancg.lwnrdb.client.internal.CollectionOps;
import io.github.christiancg.lwnrdb.client.internal.ConnectionPool;
import io.github.christiancg.lwnrdb.client.internal.PinnedChannel;
import io.github.christiancg.lwnrdb.client.internal.ResponseDecoder;
import io.github.christiancg.lwnrdb.client.mapping.JacksonCodec;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Transaction implements AutoCloseable {
    private final ConnectionPool.Lease lease;
    private final ClientContext context;
    private final String id;
    private final AtomicBoolean finished = new AtomicBoolean();

    Transaction(ConnectionPool.Lease lease, ClientContext parent) {
        this.lease = lease;
        this.context = parent.withChannel(new PinnedChannel(lease.connection(), parent.requestTimeout()));
        try {
            final var reply = context.channel().send(Operation.START_TRANSACTION, context.encoder().startTransaction());
            this.id = ResponseDecoder.textOrNull(reply.get("transactionId"));
        } catch (ConnectionLostException e) {
            finish(true);
            throw e;
        } catch (LwnrdbException e) {
            finish(false);
            throw e;
        }
    }

    public String id() {
        return id;
    }

    public boolean isOpen() {
        return !finished.get();
    }

    public TransactionCollection<Document> collection(String database, String collection) {
        return collection(database, collection, DocumentCodec.identity());
    }

    public <T> TransactionCollection<T> collection(String database, String collection, Class<T> type) {
        return collection(database, collection, new JacksonCodec<>(type, context.json()));
    }

    public <T> TransactionCollection<T> collection(String database, String collection, DocumentCodec<T> codec) {
        requireOpen();
        return new TransactionCollection<>(
                new CollectionOps<>(context, context.channel(), database, collection, codec));
    }

    public void commit() {
        end(Operation.COMMIT_TRANSACTION);
    }

    public void rollback() {
        end(Operation.ROLLBACK_TRANSACTION);
    }

    @Override
    public void close() {
        if (isOpen()) {
            rollback();
        }
    }

    private void end(Operation operation) {
        requireOpen();
        var discard = false;
        try {
            context.channel().send(operation,
                    operation == Operation.COMMIT_TRANSACTION
                            ? context.encoder().commitTransaction()
                            : context.encoder().rollbackTransaction());
        } catch (UnknownOutcomeException | ConnectionLostException e) {
            discard = true;
            throw e;
        } finally {
            finish(discard);
        }
    }

    private void finish(boolean discard) {
        if (finished.compareAndSet(false, true)) {
            if (discard) {
                lease.discard();
            }
            lease.close();
        }
    }

    private void requireOpen() {
        if (!isOpen()) {
            throw new IllegalStateException("the transaction is already committed or rolled back");
        }
    }
}
