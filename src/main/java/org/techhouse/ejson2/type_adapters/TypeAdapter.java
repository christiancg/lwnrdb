package org.techhouse.ejson2.type_adapters;

import org.techhouse.ejson2.elements.JsonBaseElement;

public interface TypeAdapter<T> {
    String toJson(T value);
    T fromJson(JsonBaseElement value);
}
