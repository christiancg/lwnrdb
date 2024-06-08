package org.techhouse.ejson.type_adapters;

import org.techhouse.ejson.elements.JsonBaseElement;

public interface TypeAdapter<T> {
    String toJson(T value);
    T fromJson(JsonBaseElement value);
}
