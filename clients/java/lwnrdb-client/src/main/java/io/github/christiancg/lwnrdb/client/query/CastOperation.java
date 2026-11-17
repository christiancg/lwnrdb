package io.github.christiancg.lwnrdb.client.query;

import io.github.christiancg.lwnrdb.client.values.CustomType;
import java.util.Objects;

public record CastOperation(String field, CastType type, CustomType customType) implements MidOperation {
    public CastOperation {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(type, "type");
        if (type == CastType.JSON_CUSTOM && customType == null) {
            throw new IllegalArgumentException("a JSON_CUSTOM cast names its custom type");
        }
        if (type != CastType.JSON_CUSTOM && customType != null) {
            throw new IllegalArgumentException("only a JSON_CUSTOM cast names a custom type");
        }
    }
}
