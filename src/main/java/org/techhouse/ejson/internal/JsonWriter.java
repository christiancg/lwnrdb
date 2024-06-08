package org.techhouse.ejson.internal;

import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonWriter {
    public <T> String toJson(T obj, Class<T> tClass) {
        final var typeAdapter = TypeAdapterFactory.getAdapter(tClass);
        return typeAdapter.toJson(obj);
    }
}
