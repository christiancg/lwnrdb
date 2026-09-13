package org.techhouse.ejson.type_adapters.impl;

import java.util.Objects;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class JsonBaseElementTypeAdapter implements TypeAdapter<JsonBaseElement> {
    @Override
    public String toJson(JsonBaseElement value) {
        final var out = new StringBuilder();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(JsonBaseElement value, StringBuilder out) {
        switch (value.getJsonType()) {
            case NULL -> out.append("null");
            case BOOLEAN -> TypeAdapterFactory.getAdapter(Boolean.class).toJson(value.asJsonBoolean().getValue(), out);
            case STRING -> TypeAdapterFactory.getAdapter(String.class).toJson(value.asJsonString().getValue(), out);
            case CUSTOM -> TypeAdapterFactory.getAdapter(String.class).toJson(((JsonCustom<?>) value).getValue(), out);
            case NUMBER -> TypeAdapterFactory.getAdapter(Number.class).toJson(value.asJsonNumber().getValue(), out);
            case ARRAY ->
                Objects.requireNonNull(TypeAdapterFactory.getAdapter(JsonArray.class)).toJson(value.asJsonArray(), out);
            case OBJECT -> Objects.requireNonNull(TypeAdapterFactory.getAdapter(JsonObject.class))
                    .toJson(value.asJsonObject(), out);
            case null, default -> throw new IllegalStateException("Unexpected value: " + value.getJsonType());
        }
    }

    @Override
    public JsonBaseElement fromJson(JsonBaseElement value) {
        return value;
    }
}
