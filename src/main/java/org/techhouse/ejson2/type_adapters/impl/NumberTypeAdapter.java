package org.techhouse.ejson2.type_adapters.impl;

import org.techhouse.ejson2.elements.JsonBaseElement;
import org.techhouse.ejson2.type_adapters.TypeAdapter;

public class NumberTypeAdapter implements TypeAdapter<Number> {
    @Override
    public String toJson(Number value) {
        return value.toString();
    }

    @Override
    public Number fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.DOUBLE) {
            return value.asJsonDouble().getValue();
        } else {
            return null;
        }
    }
}
