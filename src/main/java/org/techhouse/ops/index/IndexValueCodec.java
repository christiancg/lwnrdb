package org.techhouse.ops.index;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;

public final class IndexValueCodec {
    private IndexValueCodec() {
    }

    public static JsonBaseElement indexValueToElement(Object value) {
        return switch (value) {
            case null -> JsonNull.INSTANCE;
            case JsonBaseElement element -> element;
            case Number number -> numberToElement(number);
            case Boolean bool -> new JsonBoolean(bool);
            case String string -> new JsonString(string);
            default -> throw new IllegalStateException("Unexpected index value: " + value);
        };
    }

    public static Object elementToLookupValue(JsonBaseElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return switch (element.asJsonPrimitive()) {
                case JsonCustom<?> c -> c;
                case JsonNumber n -> n.getValue();
                case JsonBoolean b -> b.getValue();
                case JsonString s -> s.getValue();
                default -> null;
            };
        }
        return null;
    }

    // Field indexes persist numbers as doubles while documents keep integral ones as ints; normalize so
    // an index-derived number hashes equal to the same value read from a document (JOIN keys rely on it).
    private static JsonBaseElement numberToElement(Number number) {
        final var asDouble = number.doubleValue();
        if (asDouble % 1.0 == 0 && asDouble >= Integer.MIN_VALUE && asDouble <= Integer.MAX_VALUE) {
            return new JsonNumber((int) asDouble);
        }
        return new JsonNumber(asDouble);
    }
}
