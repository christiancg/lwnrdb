package org.techhouse.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import org.techhouse.ejson.elements.JsonArray;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.elements.JsonString;

public final class JsonUtils {
    private JsonUtils() {
    }

    // Produces a stable, type-disambiguated textual form of a JSON element so that two values that
    // are equal (JsonObject/JsonArray equals) always serialize identically. Object members are
    // emitted sorted by key (object equality is key-order independent); array elements keep their
    // order (array equality is order dependent); integral numbers drop their trailing ".0" so a
    // query value of 1 matches a stored 1.0. Used only to feed hashElement.
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

    // Hashes the whole element (object or array, possibly nested) into a hex SHA-256 string used as
    // the element-match index key. Equal values hash equally; the hex form is separator/newline-safe
    // so it slots straight into the existing value|ids index line format.
    public static String hashElement(JsonBaseElement element) {
        try {
            final var digest = MessageDigest.getInstance("SHA-256");
            final var bytes = digest.digest(canonicalize(element).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public static boolean hasInPath(JsonObject obj, String path) {
        var currentPart = obj;
        final var parts = path.split("\\.");
        for (String part : parts) {
            final var step = currentPart.get(part);
            if (step == null) {
                return false;
            } else if (step.isJsonObject()) {
                currentPart = step.asJsonObject();
            }
        }
        return true;
    }

    public static JsonBaseElement getFromPath(JsonObject obj, String path) {
        JsonBaseElement result = JsonNull.INSTANCE;
        var currentPart = obj;
        final var parts = path.split("\\.");
        for (String part : parts) {
            final var step = currentPart.get(part);
            if (step == null) {
                return JsonNull.INSTANCE;
            } else if (step.isJsonObject()) {
                currentPart = step.asJsonObject();
            }
            result = step;
        }
        return result;
    }

    public static int sortFunctionAscending(JsonObject o1, JsonObject o2, String fieldName) {
        final var o1Field = JsonUtils.getFromPath(o1, fieldName);
        final var o2Field = JsonUtils.getFromPath(o2, fieldName);
        if (o1Field == JsonNull.INSTANCE && o2Field == JsonNull.INSTANCE) {
            return 0;
        } else if (o1Field == JsonNull.INSTANCE) {
            return 1;
        } else if (o2Field == JsonNull.INSTANCE) {
            return -1;
        } else {
            if (o1Field.isJsonPrimitive()) {
                if (o2Field.isJsonPrimitive()) {
                    final var o1Primitive = o1Field.asJsonPrimitive();
                    final var o2Primitive = o2Field.asJsonPrimitive();
                    if (o1Primitive.isJsonCustom() && o2Primitive.isJsonCustom()
                            && o1Primitive.getClass().isAssignableFrom(o2Primitive.getClass())) {
                        return o1Primitive.asJsonCustom().getValue().compareTo(o2Primitive.asJsonCustom().getValue());
                    } else if (o1Primitive.isJsonString() && o2Primitive.isJsonString()) {
                        return o1Primitive.asJsonString().getValue().compareTo(o2Primitive.asJsonString().getValue());
                    } else if (o1Primitive.isJsonNumber() && o2Primitive.isJsonNumber()) {
                        return Double.compare(o1Primitive.asJsonNumber().getValue().doubleValue(),
                                o2Primitive.asJsonNumber().getValue().doubleValue());
                    } else if (o1Primitive.isJsonBoolean() && o2Primitive.isJsonBoolean()) {
                        return o1Primitive.asJsonBoolean().getValue() ? -1 : 1;
                    }
                    return 1;
                } else {
                    return 1;
                }
            } else {
                return -1;
            }
        }
    }

    public static int sortFunctionDescending(JsonObject o1, JsonObject o2, String fieldName) {
        final var o1Field = JsonUtils.getFromPath(o1, fieldName);
        final var o2Field = JsonUtils.getFromPath(o2, fieldName);
        if (o1Field == JsonNull.INSTANCE && o2Field == JsonNull.INSTANCE) {
            return 0;
        } else if (o1Field == JsonNull.INSTANCE) {
            return 1;
        } else if (o2Field == JsonNull.INSTANCE) {
            return -1;
        } else {
            if (o1Field.isJsonPrimitive()) {
                if (o2Field.isJsonPrimitive()) {
                    final var o1Primitive = o1Field.asJsonPrimitive();
                    final var o2Primitive = o2Field.asJsonPrimitive();
                    if (o1Primitive.isJsonCustom() && o2Primitive.isJsonCustom()
                            && o1Primitive.getClass().isAssignableFrom(o2Primitive.getClass())) {
                        return o2Primitive.asJsonCustom().getValue().compareTo(o1Primitive.asJsonCustom().getValue());
                    } else if (o1Primitive.isJsonString() && o2Primitive.isJsonString()) {
                        return o2Primitive.asJsonString().getValue().compareTo(o1Primitive.asJsonString().getValue());
                    } else if (o1Primitive.isJsonNumber() && o2Primitive.isJsonNumber()) {
                        return Double.compare(o2Primitive.asJsonNumber().getValue().doubleValue(),
                                o1Primitive.asJsonNumber().getValue().doubleValue());
                    } else if (o1Primitive.isJsonBoolean() && o2Primitive.isJsonBoolean()) {
                        return o2Primitive.asJsonBoolean().getValue() ? 1 : -1;
                    }
                    return 1;
                } else {
                    return 1;
                }
            } else {
                return -1;
            }
        }
    }
}
