package io.github.christiancg.lwnrdb.client.query;

import java.util.Objects;

public record FieldFilter(FieldOperator operator, String field, Object value) implements Filter {
    public FieldFilter {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(field, "field");
    }
}
