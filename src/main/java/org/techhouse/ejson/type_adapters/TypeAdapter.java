package org.techhouse.ejson.type_adapters;

import org.techhouse.ejson.elements.JsonBaseElement;

public interface TypeAdapter<T> {
    String toJson(T value);
    T fromJson(JsonBaseElement value);

    default void toJson(T value, StringBuilder out) {
        out.append(toJson(value));
    }
}
