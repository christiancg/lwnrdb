package org.techhouse.ops.index;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;

// Converts between an index entry's stored value and the JSON element the pipeline works with.
public final class IndexValueCodec {
    private IndexValueCodec() {
    }

    // Converts a FieldIndexEntry value back to its wire element so it can be used as a group key,
    // distinct value, or join key. Numbers/strings/booleans are stored as raw Java types; custom
    // types and nulls are already JsonBaseElements and pass through unchanged.
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

    // Inverse of indexValueToElement: converts a JsonBaseElement (local join value) to the raw
    // Java type used as the key in a field index, so it can be passed to cache.getIdsFromIndex.
    // Returns null for JsonNull, JsonObject, JsonArray, or unknown types — those callers must skip
    // the index lookup for that value (null is handled separately; objects/arrays use hash indexes
    // which are not suitable for exact-key join lookups).
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

    // Field indexes persist numbers as doubles, but documents represent integral numbers as
    // integers; normalize so an index-derived number hashes and compares equal to the same value
    // read from a document (this matters for JOIN key lookups, which rely on hashCode).
    private static JsonBaseElement numberToElement(Number number) {
        final var asDouble = number.doubleValue();
        if (asDouble % 1.0 == 0 && asDouble >= Integer.MIN_VALUE && asDouble <= Integer.MAX_VALUE) {
            return new JsonNumber((int) asDouble);
        }
        return new JsonNumber(asDouble);
    }
}
