package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.JsonStrings;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonObjectTypeAdapter implements TypeAdapter<JsonObject> {

    @Override
    public String toJson(JsonObject value) {
        final var out = new StringBuilder();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonObject value, StringBuilder out) {
        final var elementAdapter = TypeAdapterFactory.getAdapter(JsonBaseElement.class);
        out.append('{');
        var first = true;
        for (final var entry : value.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append('"').append(JsonStrings.escape(entry.getKey())).append("\":");
            elementAdapter.toJson(entry.getValue(), out);
        }
        out.append('}');
    }

    @Override
    public JsonObject fromJson(JsonBaseElement value) {
        return value.getJsonType() == JsonBaseElement.JsonType.OBJECT ? value.asJsonObject() : null;
    }
}
