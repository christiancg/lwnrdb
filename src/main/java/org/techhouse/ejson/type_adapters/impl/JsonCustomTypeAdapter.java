package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonCustomTypeAdapter implements TypeAdapter<JsonCustom<?>> {

    @Override
    public String toJson(JsonCustom<?> value) {
        return TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(value);
    }

    @Override
    public JsonCustom<?> fromJson(JsonBaseElement value) {
        if (value instanceof JsonCustom) {
            return (JsonCustom<?>) TypeAdapterFactory.getAdapter(JsonBaseElement.class).fromJson(value);
        } else {
            return null;
        }
    }
}
