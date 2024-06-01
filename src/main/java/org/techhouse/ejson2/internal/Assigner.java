package org.techhouse.ejson2.internal;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.type_adapters.TypeAdapterFactory;

public class Assigner {
    public static <T> T assign(JsonObject parsed, Class<T> tClass) {
        final var adapter = TypeAdapterFactory.getAdapter(tClass);
        return adapter.fromJson(parsed);
    }
}
