package org.techhouse.support;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Supplier;
import org.techhouse.ejson.EJson;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.internal.JsonParser;
import org.techhouse.ioc.IocContainer;

public final class ParserOutcomes {
    private static final LegacyJsonReader LEGACY = new LegacyJsonReader();

    private ParserOutcomes() {
    }

    public static String current(String input, int maxDepth, int maxTokens) {
        return outcome(() -> JsonParser.parse(input, maxDepth, maxTokens));
    }

    public static String currentFromBytes(byte[] utf8, int maxDepth, int maxTokens) {
        final var padded = new byte[utf8.length + 6];
        java.util.Arrays.fill(padded, (byte) '"');
        System.arraycopy(utf8, 0, padded, 3, utf8.length);
        return outcome(() -> JsonParser.parse(padded, 3, 3 + utf8.length, maxDepth, maxTokens));
    }

    public static String legacy(String input, int maxDepth, int maxTokens) {
        return outcome(() -> LEGACY.parse(input, maxDepth, maxTokens));
    }

    public static JsonBaseElement legacyTree(String input) {
        return LEGACY.parse(input, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }

    public static String outcome(Supplier<JsonBaseElement> parse) {
        try {
            final var tree = parse.get();
            final var shape = new StringBuilder("OK ");
            shape(tree, shape);
            return shape.append(" => ").append(IocContainer.get(EJson.class).toJson(tree)).toString();
        } catch (RuntimeException e) {
            return "ERR " + e.getClass().getName() + ": " + e.getMessage();
        }
    }

    private static String customValueText(Object customValue) {
        return customValue instanceof double[] components ? Arrays.toString(components) : String.valueOf(customValue);
    }

    public static void shape(JsonBaseElement element, StringBuilder out) {
        switch (element) {
            case JsonObject object -> {
                out.append('{');
                for (final Map.Entry<String, JsonBaseElement> member : object.entrySet()) {
                    out.append(member.getKey().length()).append(':').append(member.getKey()).append('=');
                    shape(member.getValue(), out);
                    out.append(',');
                }
                out.append('}');
            }
            case JsonArray array -> {
                out.append('[');
                for (final var item : array) {
                    shape(item, out);
                    out.append(',');
                }
                out.append(']');
            }
            case JsonCustom<?> custom -> out.append(custom.getClass().getSimpleName()).append('(')
                    .append(custom.getValue()).append('|').append(customValueText(custom.getCustomValue())).append(')');
            case JsonNumber number -> out.append(number.getValue().getClass().getSimpleName()).append('(')
                    .append(number.getValue()).append(')');
            case JsonPrimitive<?> primitive ->
                out.append(primitive.getClass().getSimpleName()).append('(').append(primitive.getValue()).append(')');
            default -> out.append(element.getClass().getSimpleName());
        }
    }
}
