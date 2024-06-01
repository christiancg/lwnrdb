package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson2.elements.JsonArray;
import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.elements.JsonObject;
import org.techhouse.ejson2.type_adapters.TypeAdapter;
import org.techhouse.ejson2.type_adapters.TypeAdapterFactory;

import java.util.Objects;

public class JsonBaseElementTypeAdapter implements TypeAdapter<JsonBaseElement> {
    @Override
    public String toJson(JsonBaseElement value) {
        return switch (value.getJsonType()) {
            case NULL -> "null";
            case BOOLEAN -> value.asJsonBoolean().getValue().toString();
            case STRING -> '"' + value.asJsonString().getValue() + '"';
            case DOUBLE -> value.asJsonDouble().getValue().toString();
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
