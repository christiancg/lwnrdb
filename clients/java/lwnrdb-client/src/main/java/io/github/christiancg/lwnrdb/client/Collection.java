package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.internal.CollectionOps;
import io.github.christiancg.lwnrdb.client.protocol.Operation;
import io.github.christiancg.lwnrdb.client.query.Query;
import io.github.christiancg.lwnrdb.client.scripting.Triggers;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class Collection<T> {
    private final CollectionOps<T> ops;

    Collection(CollectionOps<T> ops) {
        this.ops = ops;
    }

    public String databaseName() {
        return ops.database();
    }

    public String name() {
        return ops.collection();
    }

    public void create() {
        send(Operation.CREATE_COLLECTION);
    }

    public void drop() {
        send(Operation.DROP_COLLECTION);
    }

    public SaveResult save(T value) {
        return ops.save(Objects.requireNonNull(value, "value"));
    }

    public BulkSaveResult saveAll(List<T> values) {
        return ops.saveAll(values);
    }

    public Optional<T> findById(String id) {
        return ops.findById(id, false);
    }

    public Optional<T> findById(String id, ReadMode mode) {
        return ops.findById(id, mode == ReadMode.DIRTY);
    }

    public void delete(String id) {
        ops.delete(id);
    }

    public Query<T> query() {
        return new Query<>(ops);
    }

    public Indexes indexes() {
        return new Indexes(ops.context(), ops.database(), ops.collection());
    }

    public Schema schema() {
        return new Schema(ops.context(), ops.database(), ops.collection());
    }

    public Triggers triggers() {
        return new Triggers(ops.context(), ops.database(), ops.collection());
    }

    public Subscription listen(Query<T> query, ListenHandler<T> handler) {
        return ops.context().listens().listen(ops.database(), ops.collection(), query.steps(), ops.codec(),
                Objects.requireNonNull(handler, "handler"));
    }

    public Subscription listen(ListenHandler<T> handler) {
        return listen(query(), handler);
    }

    private void send(Operation operation) {
        final var encoder = ops.context().encoder();
        final var request = operation == Operation.CREATE_COLLECTION
                ? encoder.createCollection(ops.database(), ops.collection())
                : encoder.dropCollection(ops.database(), ops.collection());
        ops.context().channel().send(operation, request);
    }
}
