package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonOut;
import org.techhouse.ejson.internal.JsonStrings;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonObjectTypeAdapter implements TypeAdapter<JsonObject> {

    @Override
    public String toJson(JsonObject value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonObject value, JsonOut out) {
        final var elementAdapter = TypeAdapterFactory.getAdapter(JsonBaseElement.class);
        out.append('{');
        for (var i = 0; i < value.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"');
            JsonStrings.appendEscaped(out, value.keyAt(i));
            out.append("\":");
            elementAdapter.toJson(value.valueAt(i), out);
        }
        out.append('}');
    }

    @Override
    public JsonObject fromJson(JsonBaseElement value) {
        return value.getJsonType() == JsonBaseElement.JsonType.OBJECT ? value.asJsonObject() : null;
    }
}
