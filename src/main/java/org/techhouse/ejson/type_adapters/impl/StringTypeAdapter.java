package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class StringTypeAdapter implements TypeAdapter<String> {

    @Override
    public String toJson(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Override
    public String fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.STRING) {
            return value.asJsonString().getValue();
        }
        return null;
    }
}
