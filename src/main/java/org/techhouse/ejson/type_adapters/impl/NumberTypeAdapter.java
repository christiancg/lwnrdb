package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class NumberTypeAdapter implements TypeAdapter<Number> {
    @Override
    public String toJson(Number value) {
        return value != null ? value.doubleValue() % 1 != 0 ? value.toString() : String.valueOf(value.longValue()) : "null";
    }

    @Override
    public Number fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.NUMBER) {
            return value.asJsonNumber().getValue();
        } else {
            return null;
        }
    }
}
