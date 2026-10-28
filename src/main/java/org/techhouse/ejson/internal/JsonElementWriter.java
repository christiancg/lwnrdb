package org.techhouse.ejson.internal;

import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.impl.NumberTypeAdapter;

public final class JsonElementWriter {
    private JsonElementWriter() {
    }

    public static void write(JsonBaseElement element, JsonOut out) {
        switch (element.getJsonType()) {
            case NULL -> out.append("null");
            case BOOLEAN -> out.append(element.asJsonBoolean().getValue().toString());
            case STRING -> writeString(element.asJsonString().getValue(), out);
            case CUSTOM -> writeString(((JsonCustom<?>) element).getValue(), out);
            case NUMBER -> NumberTypeAdapter.appendDouble(element.asJsonNumber().doubleValue(), out);
            case ARRAY -> writeArray(element.asJsonArray(), out);
            case OBJECT -> writeObject(element.asJsonObject(), out);
            case null, default -> throw new IllegalStateException("Unexpected value: " + element.getJsonType());
        }
    }

    public static long utf8Length(JsonBaseElement element) {
        final var out = new Utf8CountingJsonOut();
        write(element, out);
        return out.length();
    }

    public static void writeString(String value, JsonOut out) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        JsonStrings.appendEscaped(out, value);
        out.append('"');
    }

    private static void writeArray(JsonArray array, JsonOut out) {
        out.append('[');
        var first = true;
        for (final var element : array) {
            if (!first) {
                out.append(',');
            }
            first = false;
            write(element, out);
        }
        out.append(']');
    }

    private static void writeObject(JsonObject object, JsonOut out) {
        out.append('{');
        for (var i = 0; i < object.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append('"');
            JsonStrings.appendEscaped(out, object.keyAt(i));
            out.append("\":");
            write(object.valueAt(i), out);
        }
        out.append('}');
    }
}
