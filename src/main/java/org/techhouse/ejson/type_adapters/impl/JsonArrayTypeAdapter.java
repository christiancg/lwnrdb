package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

import java.util.stream.Collectors;

public class JsonArrayTypeAdapter implements TypeAdapter<JsonArray> {

    @Override
    public String toJson(JsonArray value) {
        return '[' +
                value.asList().stream()
                        .map(element -> TypeAdapterFactory.getAdapter(JsonBaseElement.class).toJson(element))
                        .collect(Collectors.joining(","))
                + ']';
    }

    @Override
    public JsonArray fromJson(JsonBaseElement value) {
        return value.getJsonType() == JsonBaseElement.JsonType.ARRAY ? value.asJsonArray() : null;
    }
}
