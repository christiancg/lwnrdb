package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

import java.util.Objects;

public class JsonBaseElementTypeAdapter implements TypeAdapter<JsonBaseElement> {
    @Override
    public String toJson(JsonBaseElement value) {
        return switch (value.getJsonType()) {
            case NULL -> "null";
            case BOOLEAN -> TypeAdapterFactory.getAdapter(Boolean.class).toJson(value.asJsonBoolean().getValue());
            case STRING -> TypeAdapterFactory.getAdapter(String.class).toJson(value.asJsonString().getValue());
            case CUSTOM -> TypeAdapterFactory.getAdapter(String.class).toJson(((JsonCustom<?>)value).getValue());
            case NUMBER -> TypeAdapterFactory.getAdapter(Number.class).toJson(value.asJsonNumber().getValue());
            case ARRAY -> Objects.requireNonNull(TypeAdapterFactory.getAdapter(JsonArray.class)).toJson(value.asJsonArray());
            case OBJECT -> Objects.requireNonNull(TypeAdapterFactory.getAdapter(JsonObject.class)).toJson(value.asJsonObject());
            case null, default -> throw new IllegalStateException("Unexpected value: " + value.getJsonType());
        };
    }

    @Override
    public JsonBaseElement fromJson(JsonBaseElement value) {
        return value;
    }
}
