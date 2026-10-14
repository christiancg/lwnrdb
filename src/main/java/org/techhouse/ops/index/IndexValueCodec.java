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
            case Number number -> new JsonNumber(number.doubleValue() + 0.0);
            case Boolean bool -> JsonBoolean.of(bool);
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
}
