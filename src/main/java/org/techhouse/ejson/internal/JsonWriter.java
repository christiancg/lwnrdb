package org.techhouse.ejson.internal;

import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonWriter {
    private static final int INITIAL_CAPACITY = 256;
    private static final int MIN_PRESIZE = 16;
    private static final int MAX_PRESIZE = 1 << 26;

    public <T> String toJson(T obj, Class<T> tClass) {
        return write(obj, tClass, INITIAL_CAPACITY).toString();
    }

    public <T> byte[] toJsonBytes(T obj, Class<T> tClass, int sizeHint) {
        return Utf8Encoder.encode(write(obj, tClass, Math.clamp(sizeHint, MIN_PRESIZE, MAX_PRESIZE)));
    }

    public <T> byte[] toJsonLine(T obj, Class<T> tClass, int sizeHint, String terminator) {
        return Utf8Encoder
                .encode(write(obj, tClass, Math.clamp(sizeHint, MIN_PRESIZE, MAX_PRESIZE)).append(terminator));
    }

    private static <T> StringBuilder write(T obj, Class<T> tClass, int capacity) {
        final var typeAdapter = TypeAdapterFactory.getAdapter(tClass);
        final var out = new StringBuilder(capacity);
        typeAdapter.toJson(obj, out);
        return out;
    }
}
