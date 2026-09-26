package org.techhouse.ejson.type_adapters.impl;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.internal.NumberFormatter;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.log.Logger;

public class NumberTypeAdapter implements TypeAdapter<Number> {
    private static final String JSON_NULL = "null";

    private final Logger logger = Logger.logFor(NumberTypeAdapter.class);

    @Override
    public String toJson(Number value) {
        if (value == null) {
            return JSON_NULL;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value.toString();
        }
        final var asDouble = value.doubleValue();
        if (!Double.isFinite(asDouble)) {
            logger.warning("Serialising the non-JSON number " + NumberFormatter.toJsString(asDouble)
                    + " failed; emitting null in its place");
            return JSON_NULL;
        }
        return NumberFormatter.toJsString(asDouble);
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
