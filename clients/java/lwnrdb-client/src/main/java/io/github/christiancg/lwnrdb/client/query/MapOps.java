package io.github.christiancg.lwnrdb.client.query;

import java.util.Objects;

public final class MapOps {
    private MapOps() {
    }

    public static MapOperation set(String field, MidOperation operation) {
        return new MapOperation(field, Objects.requireNonNull(operation, "operation"), null);
    }

    public static MapOperation remove(String field) {
        return new MapOperation(field, null, null);
    }
}
