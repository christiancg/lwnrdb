package org.techhouse.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.function.ToIntBiFunction;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonPrimitive;
import org.techhouse.ejson.elements.JsonString;

public final class JsonUtils {
    private JsonUtils() {
    }

    public static int sortFunctionAscending(JsonObject o1, JsonObject o2, String fieldName) {
        return compareAtPath(o1, o2, fieldName, JsonUtils::ascendingPrimitives);
    }

    public static int sortFunctionDescending(JsonObject o1, JsonObject o2, String fieldName) {
        return compareAtPath(o1, o2, fieldName, JsonUtils::descendingPrimitives);
    }

    private static int compareAtPath(JsonObject o1, JsonObject o2, String fieldName,
            ToIntBiFunction<JsonPrimitive<?>, JsonPrimitive<?>> comparePrimitives) {
        final var o1Field = JsonUtils.getFromPath(o1, fieldName);
        final var o2Field = JsonUtils.getFromPath(o2, fieldName);
        if (o1Field == JsonNull.INSTANCE && o2Field == JsonNull.INSTANCE) {
            return 0;
        }
        if (o1Field == JsonNull.INSTANCE) {
            return 1;
        }
        if (o2Field == JsonNull.INSTANCE) {
            return -1;
        }
        if (!o1Field.isJsonPrimitive() && !o2Field.isJsonPrimitive()) {
            return 0;
        }
        if (!o1Field.isJsonPrimitive()) {
            return -1;
        }
        if (!o2Field.isJsonPrimitive()) {
            return 1;
        }
        return comparePrimitives.applyAsInt(o1Field.asJsonPrimitive(), o2Field.asJsonPrimitive());
    }

    private static int ascendingPrimitives(JsonPrimitive<?> o1Primitive, JsonPrimitive<?> o2Primitive) {
        if (o1Primitive.isJsonCustom() && o2Primitive.isJsonCustom()
                && o1Primitive.getClass().isAssignableFrom(o2Primitive.getClass())) {
            return o1Primitive.asJsonCustom().getValue().compareTo(o2Primitive.asJsonCustom().getValue());
        }
        if (o1Primitive.isJsonString() && o2Primitive.isJsonString()) {
            return o1Primitive.asJsonString().getValue().compareTo(o2Primitive.asJsonString().getValue());
        }
        if (o1Primitive.isJsonNumber() && o2Primitive.isJsonNumber()) {
            return Double.compare(o1Primitive.asJsonNumber().getValue().doubleValue(),
                    o2Primitive.asJsonNumber().getValue().doubleValue());
        }
        if (o1Primitive.isJsonBoolean() && o2Primitive.isJsonBoolean()) {
            return Boolean.compare(o1Primitive.asJsonBoolean().getValue(), o2Primitive.asJsonBoolean().getValue());
        }
        return compareByType(o1Primitive, o2Primitive);
    }

    // Values of different types still need a total order, or each compares greater than the other and
    // TimSort rejects the whole sort. The ranking is arbitrary but must be identical on every node.
    private static int compareByType(JsonPrimitive<?> o1Primitive, JsonPrimitive<?> o2Primitive) {
        final var byRank = Integer.compare(typeRank(o1Primitive), typeRank(o2Primitive));
        return byRank != 0
                ? byRank
                : o1Primitive.getClass().getSimpleName().compareTo(o2Primitive.getClass().getSimpleName());
    }

    // Custom types extend JsonString, so they have to be ranked before the string case is considered.
    private static int typeRank(JsonPrimitive<?> primitive) {
        if (primitive.isJsonBoolean()) {
            return 0;
        }
        if (primitive.isJsonNumber()) {
            return 1;
        }
        return primitive.isJsonCustom() ? 3 : 2;
    }

    private static int descendingPrimitives(JsonPrimitive<?> o1Primitive, JsonPrimitive<?> o2Primitive) {
        return ascendingPrimitives(o2Primitive, o1Primitive);
    }

    public static String canonicalize(JsonBaseElement element) {
        final var sb = new StringBuilder();
        appendCanonical(element, sb);
        return sb.toString();
    }

    private static void appendCanonical(JsonBaseElement element, StringBuilder sb) {
        switch (element) {
            case null -> sb.append("null");
            case JsonNull ignored -> sb.append("null");
            case JsonObject object -> appendCanonicalObject(object, sb);
            case JsonArray array -> appendCanonicalArray(array, sb);
            case JsonCustom<?> custom -> sb.append(custom.getValue());
            case JsonNumber number -> sb.append(normalizeNumber(number.getValue()));
            case JsonBoolean bool -> sb.append(bool.getValue().booleanValue());
            case JsonString string -> appendCanonicalString(string.getValue(), sb);
            default -> sb.append(element);
        }
    }

    private static void appendCanonicalObject(JsonObject object, StringBuilder sb) {
        final var entries = new ArrayList<>(object.entrySet());
        entries.sort(java.util.Map.Entry.comparingByKey());
        sb.append('{');
        var first = true;
        for (final var entry : entries) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendCanonicalString(entry.getKey(), sb);
            sb.append(':');
            appendCanonical(entry.getValue(), sb);
        }
        sb.append('}');
    }

    private static void appendCanonicalArray(JsonArray array, StringBuilder sb) {
        sb.append('[');
        var first = true;
        for (final var element : array) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            appendCanonical(element, sb);
        }
        sb.append(']');
    }

    private static void appendCanonicalString(String value, StringBuilder sb) {
        sb.append('"');
        for (var i = 0; i < value.length(); i++) {
            final var c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
    }

    private static String normalizeNumber(Number value) {
        final var asDouble = value.doubleValue();
        if (asDouble % 1.0 == 0 && !Double.isInfinite(asDouble)) {
            return String.valueOf((long) asDouble);
        }
        return String.valueOf(asDouble);
    }

    public static String hashElement(JsonBaseElement element) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            final var bytes = digest.digest(canonicalize(element).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static String sha256(String value) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static JsonBaseElement resolvePath(JsonObject obj, String path) {
        if (path.indexOf('.') < 0) {
            return obj.get(path);
        }
        var limit = path.length();
        while (limit > 0 && path.charAt(limit - 1) == '.') {
            limit--;
        }
        if (limit == 0) {
            return JsonNull.INSTANCE;
        }
        var currentPart = obj;
        JsonBaseElement result = JsonNull.INSTANCE;
        var start = 0;
        while (start <= limit) {
            var dot = path.indexOf('.', start);
            if (dot < 0 || dot > limit) {
                dot = limit;
            }
            final var step = currentPart.get(path.substring(start, dot));
            if (step == null) {
                return null;
            }
            if (step.isJsonObject()) {
                currentPart = step.asJsonObject();
            }
            result = step;
            start = dot + 1;
        }
        return result;
    }

    public static boolean hasInPath(JsonObject obj, String path) {
        return resolvePath(obj, path) != null;
    }

    public static JsonBaseElement getFromPath(JsonObject obj, String path) {
        final var resolved = resolvePath(obj, path);
        return resolved == null ? JsonNull.INSTANCE : resolved;
    }

}
