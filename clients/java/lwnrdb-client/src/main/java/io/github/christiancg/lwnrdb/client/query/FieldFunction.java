package io.github.christiancg.lwnrdb.client.query;

import java.util.Objects;

public record FieldFunction(Function function, String field) implements MidOperation {
    public enum Function {
        ABS, SIZE
    }

    public FieldFunction {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(field, "field");
    }
}
