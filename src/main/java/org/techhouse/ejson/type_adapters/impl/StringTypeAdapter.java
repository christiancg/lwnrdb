package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.internal.JsonStrings;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class StringTypeAdapter implements TypeAdapter<String> {

    @Override
    public String toJson(String value) {
        if (value == null) {
            return "null";
        }
        final var out = new StringBuilder(value.length() + 2);
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(String value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else {
            out.append('"');
            JsonStrings.appendEscaped(out, value);
            out.append('"');
        }
    }

    @Override
    public String fromJson(JsonBaseElement value) {
        if (value.isJsonString()) {
            return value.asJsonString().getValue();
        }
        return null;
    }
}
