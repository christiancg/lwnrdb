package io.github.christiancg.lwnrdb.client.query;

import java.util.Objects;
import java.util.Optional;

public final class MapOperation {
    private final String field;
    private final MidOperation operation;
    private final Filter condition;

    MapOperation(String field, MidOperation operation, Filter condition) {
        this.field = Objects.requireNonNull(field, "field");
        this.operation = operation;
        this.condition = condition;
    }

    public MapOperation when(Filter condition) {
        return new MapOperation(field, operation, Objects.requireNonNull(condition, "condition"));
    }

    public String field() {
        return field;
    }

    public Optional<MidOperation> operation() {
        return Optional.ofNullable(operation);
    }

    public Optional<Filter> condition() {
        return Optional.ofNullable(condition);
    }

    public boolean removes() {
        return operation == null;
    }
}
