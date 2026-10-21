package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonElementWriter;
import org.techhouse.ejson.internal.JsonOut;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class JsonBaseElementTypeAdapter implements TypeAdapter<JsonBaseElement> {
    @Override
    public String toJson(JsonBaseElement value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonBaseElement value, JsonOut out) {
        JsonElementWriter.write(value, out);
    }

    @Override
    public JsonBaseElement fromJson(JsonBaseElement value) {
        return value;
    }
}
