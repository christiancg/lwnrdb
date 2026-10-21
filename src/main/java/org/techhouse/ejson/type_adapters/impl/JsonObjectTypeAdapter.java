package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ejson.internal.JsonOut;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class JsonObjectTypeAdapter implements TypeAdapter<JsonObject> {

    @Override
    public String toJson(JsonObject value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonObject value, JsonOut out) {
        JsonElementWriter.write(value, out);
    }

    @Override
    public JsonObject fromJson(JsonBaseElement value) {
        return value.getJsonType() == JsonBaseElement.JsonType.OBJECT ? value.asJsonObject() : null;
    }
}
