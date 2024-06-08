package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class NullTypeAdapter implements TypeAdapter<Object> {
    @Override
    public String toJson(Object value) {
        return "null";
    }

    @Override
    public Object fromJson(JsonBaseElement value) {
        return null;
    }
}
