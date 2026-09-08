package org.techhouse.ejson.internal;

import java.util.Objects;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public final class JsonPrettyWriter {
    private JsonPrettyWriter() {
    }

    public static String toJson(JsonBaseElement element, String indent) {
        final var builder = new StringBuilder();
        write(builder, element, indent, 0);
        return builder.toString();
    }

    private static void write(StringBuilder builder, JsonBaseElement element, String indent, int depth) {
        if (element == null) {
            builder.append("null");
            return;
        }
        switch (element.getJsonType()) {
            case OBJECT -> writeObject(builder, element.asJsonObject(), indent, depth);
            case ARRAY -> writeArray(builder, element.asJsonArray(), indent, depth);
            default -> builder.append(leaf(element));
        }
    }

    private static void writeObject(StringBuilder builder, JsonObject object, String indent, int depth) {
        if (object.entrySet().isEmpty()) {
            builder.append("{}");
            return;
        }
        builder.append('{');
        var first = true;
        for (final var entry : object.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            newLine(builder, indent, depth + 1);
            builder.append('"').append(JsonStrings.escape(entry.getKey())).append("\": ");
            write(builder, entry.getValue(), indent, depth + 1);
        }
        newLine(builder, indent, depth);
        builder.append('}');
    }

    private static void writeArray(StringBuilder builder, JsonArray array, String indent, int depth) {
        if (array.asList().isEmpty()) {
            builder.append("[]");
            return;
        }
        builder.append('[');
        var first = true;
        for (final var item : array.asList()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            newLine(builder, indent, depth + 1);
            write(builder, item, indent, depth + 1);
        }
        newLine(builder, indent, depth);
        builder.append(']');
    }

    private static void newLine(StringBuilder builder, String indent, int depth) {
        builder.append('\n');
        builder.repeat(Objects.requireNonNull(indent), depth);
    }

    private static String leaf(JsonBaseElement element) {
        return Objects.requireNonNull(TypeAdapterFactory.getAdapter(JsonBaseElement.class)).toJson(element);
    }
}
