package io.github.christiancg.lwnrdb.client.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;

public final class FieldCondition {
    private final String field;

    FieldCondition(String field) {
        this.field = Objects.requireNonNull(field, "field");
    }

    public Filter eq(Object value) {
        return of(FieldOperator.EQUALS, value);
    }

    public Filter ne(Object value) {
        return of(FieldOperator.NOT_EQUALS, value);
    }

    public Filter gt(Object value) {
        return of(FieldOperator.GREATER_THAN, value);
    }

    public Filter gte(Object value) {
        return of(FieldOperator.GREATER_THAN_EQUALS, value);
    }

    public Filter lt(Object value) {
        return of(FieldOperator.SMALLER_THAN, value);
    }

    public Filter lte(Object value) {
        return of(FieldOperator.SMALLER_THAN_EQUALS, value);
    }

    public Filter in(Object... values) {
        return in(Arrays.asList(values));
    }

    public Filter in(Collection<?> values) {
        return of(FieldOperator.IN, listOf(values));
    }

    public Filter notIn(Object... values) {
        return notIn(Arrays.asList(values));
    }

    public Filter notIn(Collection<?> values) {
        return of(FieldOperator.NOT_IN, listOf(values));
    }

    public Filter contains(Object value) {
        return of(FieldOperator.CONTAINS, value);
    }

    private Filter of(FieldOperator operator, Object value) {
        return new FieldFilter(operator, field, value);
    }

    private static Object listOf(Collection<?> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
