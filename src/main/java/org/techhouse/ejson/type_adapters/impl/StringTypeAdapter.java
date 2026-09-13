package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.internal.JsonStrings;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class StringTypeAdapter implements TypeAdapter<String> {

    @Override
    public String toJson(String value) {
        return value == null ? "null" : "\"" + JsonStrings.escape(value) + "\"";
    }

    @Override
    public void toJson(String value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else {
            out.append('"').append(JsonStrings.escape(value)).append('"');
        }
    }

    @Override
    public String fromJson(JsonBaseElement value) {
        if (value.getJsonType() == JsonBaseElement.JsonType.STRING) {
            return value.asJsonString().getValue();
        }
        return null;
    }
}
