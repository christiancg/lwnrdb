package io.github.christiancg.lwnrdb.client;

import io.github.christiancg.lwnrdb.client.internal.CollectionOps;
import io.github.christiancg.lwnrdb.client.query.Filter;
import io.github.christiancg.lwnrdb.client.query.Query;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class TransactionCollection<T> {
    private final CollectionOps<T> ops;

    TransactionCollection(CollectionOps<T> ops) {
        this.ops = ops;
    }

    public String databaseName() {
        return ops.database();
    }

    public String name() {
        return ops.collection();
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

    public void delete(String id) {
        ops.delete(id);
    }

    public long deleteWhere(Filter condition) {
        return ops.deleteWhere(condition);
    }

    public long updateWhere(Filter condition, Object patch) {
        return ops.updateWhere(condition, patch);
    }

    public Query<T> query() {
        return new Query<>(ops);
    }
}
