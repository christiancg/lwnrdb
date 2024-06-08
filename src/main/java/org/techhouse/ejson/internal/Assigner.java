package org.techhouse.ejson.internal;

import org.techhouse.ejson.elements.*;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class Assigner {
    public static <T> T assign(JsonObject parsed, Class<T> tClass) {
        final var adapter = TypeAdapterFactory.getAdapter(tClass);
        return adapter.fromJson(parsed);
    }
}
