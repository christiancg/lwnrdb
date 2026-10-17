package org.techhouse.ejson.internal;

import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonWriter {
    private static final int INITIAL_CAPACITY = 256;
    private static final int MIN_PRESIZE = 16;
    private static final int MAX_PRESIZE = 1 << 26;

    public <T> String toJson(T obj, Class<T> tClass) {
        final var out = new CharJsonOut(new StringBuilder(INITIAL_CAPACITY));
        TypeAdapterFactory.getAdapter(tClass).toJson(obj, out);
        return out.toString();
    }

    public <T> byte[] toJsonBytes(T obj, Class<T> tClass, int sizeHint) {
        return toJsonLine(obj, tClass, sizeHint, "");
    }

    public <T> EncodedJson toJsonFrame(T obj, Class<T> tClass, int sizeHint) {
        final var out = Utf8JsonOut.acquire(Math.clamp(sizeHint, MIN_PRESIZE, MAX_PRESIZE));
        try {
            TypeAdapterFactory.getAdapter(tClass).toJson(obj, out);
            return out.handOver();
        } finally {
            out.release();
        }
    }

    public <T> byte[] toJsonLine(T obj, Class<T> tClass, int sizeHint, String terminator) {
        final var out = Utf8JsonOut.acquire(Math.clamp(sizeHint, MIN_PRESIZE, MAX_PRESIZE));
        try {
            TypeAdapterFactory.getAdapter(tClass).toJson(obj, out);
            out.append(terminator);
            return out.toByteArray();
        } finally {
            out.release();
        }
    }
}
