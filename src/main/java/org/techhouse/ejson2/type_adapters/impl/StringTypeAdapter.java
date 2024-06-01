package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.type_adapters.TypeAdapter;

public class StringTypeAdapter implements TypeAdapter<String> {

    @Override
    public String toJson(String value) {
        return "\"" + value + "\"";
    }

    @Override
    public String fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.STRING) {
            return value.asJsonString().getValue();
        }
        return null;
    }
}
