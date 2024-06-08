package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

import java.util.Objects;
import java.util.stream.Collectors;

public class JsonObjectTypeAdapter implements TypeAdapter<JsonObject> {

    @Override
    public String toJson(JsonObject value) {
        return '{' +
                value.entrySet().stream()
                        .map(stringJsonBaseElementEntry -> "\"" + stringJsonBaseElementEntry.getKey() + "\":" +
                                Objects.requireNonNull(TypeAdapterFactory.getAdapter(JsonBaseElement.class))
                                        .toJson(stringJsonBaseElementEntry.getValue())
                        ).collect(Collectors.joining(","))
                + '}';
    }

    @Override
    public JsonObject fromJson(JsonBaseElement value) {
        return value.getJsonType() == JsonBaseElement.JsonType.OBJECT ? value.asJsonObject() : null;
    }
}
