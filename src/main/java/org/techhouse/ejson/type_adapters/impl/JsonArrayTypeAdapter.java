package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ejson.internal.JsonOut;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class JsonArrayTypeAdapter implements TypeAdapter<JsonArray> {

    @Override
    public String toJson(JsonArray value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonArray value, JsonOut out) {
        JsonElementWriter.write(value, out);
    }

    @Override
    public JsonArray fromJson(JsonBaseElement value) {
        return value == null
                ? null
                : value.getJsonType() == JsonBaseElement.JsonType.ARRAY ? value.asJsonArray() : null;
    }
}
