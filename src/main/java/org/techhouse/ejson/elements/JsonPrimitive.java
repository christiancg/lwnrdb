package org.techhouse.ejson.elements;

import lombok.Data;

@Data
public class JsonPrimitive<T> extends JsonBaseElement {
    protected T value;

    protected void set(T value) {
        this.value = value;
    }

    protected T get() {
        return this.value;
    }

    @Override
    public int hashCode() {
        if (value == null) {
            return 31;
        }
        if (value instanceof Double) {
            return getValue().hashCode();
        }
        return value.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        JsonPrimitive<?> other = (JsonPrimitive<?>)obj;
        if (value == null) {
            return other.value == null;
        }
        if (value instanceof Double && other.value instanceof Double) {
            double a = (Double) value;
            double b = (Double) other.value;
            return a == b || (Double.isNaN(a) && Double.isNaN(b));
        }
        return value.equals(other.value);
    }

    @Override
    public JsonBaseElement deepCopy() {
        return switch (this) {
            case JsonDouble ignored -> new JsonDouble((Double) getValue());
            case JsonBoolean ignored -> new JsonBoolean((Boolean) getValue());
            case JsonString ignored -> new JsonString((String) getValue());
            default -> null;
        };
    }
}
