package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonPrimitiveTypeAdapter implements TypeAdapter<JsonPrimitive<?>> {

    @Override
    public String toJson(JsonPrimitive<?> value) {
        return TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(value);
    }

    @Override
    public JsonPrimitive<?> fromJson(JsonBaseElement value) {
        return switch (value.getJsonType()) {
            case BOOLEAN, NUMBER, STRING -> (JsonPrimitive<?>) TypeAdapterFactory.getAdapter(JsonBaseElement.class).fromJson(value);
            default -> null;
        };
    }
}
