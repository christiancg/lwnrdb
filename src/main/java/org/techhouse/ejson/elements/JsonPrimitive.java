package org.techhouse.ejson.elements;

import java.util.Objects;

public abstract class JsonPrimitive<T> extends JsonBaseElement {
    protected abstract T get();

    public T getValue() {
        return get();
    }

    @Override
    public int hashCode() {
        final var value = get();
        return value == null ? 31 : value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final var value = get();
        final var other = ((JsonPrimitive<?>) obj).get();
        return Objects.equals(value, other);
    }

    @Override
    public JsonBaseElement deepCopy() {
        return this;
    }
}
