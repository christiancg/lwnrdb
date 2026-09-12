package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonArrayTypeAdapter implements TypeAdapter<JsonArray> {

    @Override
    public String toJson(JsonArray value) {
        final var elementAdapter = TypeAdapterFactory.getAdapter(JsonBaseElement.class);
        final var builder = new StringBuilder("[");
        var first = true;
        for (final var element : value) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(elementAdapter.toJson(element));
        }
        return builder.append(']').toString();
    }

    @Override
    public JsonArray fromJson(JsonBaseElement value) {
        return value == null
                ? null
                : value.getJsonType() == JsonBaseElement.JsonType.ARRAY ? value.asJsonArray() : null;
    }
}
