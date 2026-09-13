package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonArrayTypeAdapter implements TypeAdapter<JsonArray> {

    @Override
    public String toJson(JsonArray value) {
        final var out = new StringBuilder();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonArray value, StringBuilder out) {
        final var elementAdapter = TypeAdapterFactory.getAdapter(JsonBaseElement.class);
        out.append('[');
        var first = true;
        for (final var element : value) {
            if (!first) {
                out.append(',');
            }
            first = false;
            elementAdapter.toJson(element, out);
        }
        out.append(']');
    }

    @Override
    public JsonArray fromJson(JsonBaseElement value) {
        return value == null
                ? null
                : value.getJsonType() == JsonBaseElement.JsonType.ARRAY ? value.asJsonArray() : null;
    }
}
