package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.type_adapters.TypeAdapter;

public class BooleanTypeAdapter implements TypeAdapter<Boolean> {
    @Override
    public String toJson(Boolean value) {
        return value ? "true" : "false";
    }

    @Override
    public Boolean fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.BOOLEAN) {
            return value.asJsonBoolean().getValue();
        } else if (value.getJsonType() == JsonBaseElement.JsonType.STRING) {
            return Boolean.getBoolean(value.asJsonString().getValue());
        } else {
            return null;
        }
    }
}
