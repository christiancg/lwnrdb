package org.techhouse.ejson.internal;

import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonWriter {
    private static final int INITIAL_CAPACITY = 256;

    public <T> String toJson(T obj, Class<T> tClass) {
        final var typeAdapter = TypeAdapterFactory.getAdapter(tClass);
        final var out = new StringBuilder(INITIAL_CAPACITY);
        typeAdapter.toJson(obj, out);
        return out.toString();
    }
}
