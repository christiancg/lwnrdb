package org.techhouse.ejson.type_adapters.impl;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public class NumberTypeAdapter implements TypeAdapter<Number> {
    @Override
    public String toJson(Number value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value.toString();
        }
        return NumberFormatter.toJsString(value.doubleValue());
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
