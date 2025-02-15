package org.techhouse.utils;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonObject;

public class JsonUtils {
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
                    if (o1Primitive.isJsonCustom() && o2Primitive.isJsonCustom() &&
                            o1Primitive.getClass().isAssignableFrom(o2Primitive.getClass())) {
                        return o1Primitive.asJsonCustom().getValue().compareTo(o2Primitive.asJsonCustom().getValue());
                    } else if (o1Primitive.isJsonString() && o2Primitive.isJsonString()) {
                        return o1Primitive.asJsonString().getValue().compareTo(o2Primitive.asJsonString().getValue());
                    } else if (o1Primitive.isJsonNumber() && o2Primitive.isJsonNumber()) {
                        return Double.compare(o1Primitive.asJsonNumber().getValue().doubleValue(), o2Primitive.asJsonNumber().getValue().doubleValue());
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
                    if (o1Primitive.isJsonCustom() && o2Primitive.isJsonCustom() &&
                            o1Primitive.getClass().isAssignableFrom(o2Primitive.getClass())) {
                        return o2Primitive.asJsonCustom().getValue().compareTo(o1Primitive.asJsonCustom().getValue());
                    } else if (o1Primitive.isJsonString() && o2Primitive.isJsonString()) {
                        return o2Primitive.asJsonString().getValue().compareTo(o1Primitive.asJsonString().getValue());
                    } else if (o1Primitive.isJsonNumber() && o2Primitive.isJsonNumber()) {
                        return Double.compare(o2Primitive.asJsonNumber().getValue().doubleValue(), o1Primitive.asJsonNumber().getValue().doubleValue());
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
