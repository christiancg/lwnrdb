package org.techhouse.utils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class JsonUtils {
    public static boolean hasInPath(JsonObject obj, String path) {
        var currentPart = obj;
        final var parts = path.split("\\.");
        for (String part : parts) {
            final var step = currentPart.get(part);
            if (step == null) {
                return false;
            } else if (step.isJsonObject()) {
                currentPart = step.getAsJsonObject();
            }
        }
        return true;
    }

    public static JsonElement getFromPath(JsonObject obj, String path) {
        JsonElement result = null;
        var currentPart = obj;
        final var parts = path.split("\\.");
        for (String part : parts) {
            final var step = currentPart.get(part);
            if (step == null) {
                return null;
            } else if (step.isJsonObject()) {
                currentPart = step.getAsJsonObject();
            }
            result = step;
        }
        return result;
    }

    public static boolean matchesType(JsonObject o1, JsonObject o2, String fieldName) {
        final var o1Field = o1.get(fieldName);
        final var o2Field = o2.get(fieldName);
        if (o1Field != null && o2Field != null) {
            if (o1Field.isJsonPrimitive()) {
                if (o2.isJsonPrimitive()) {
                    final var o1Primitive = o1Field.getAsJsonPrimitive();
                    final var o2Primitive = o2Field.getAsJsonPrimitive();
                    return (o1Primitive.isString() && o2Primitive.isString()) ||
                            (o1Primitive.isNumber() && o2Primitive.isNumber()) ||
                            (o1Primitive.isBoolean() && o2Primitive.isBoolean());
                }
            } else if (o1Field.isJsonArray()) {
                return o2.isJsonArray();
            } else if (o1Field.isJsonObject()) {
                return o2.isJsonObject();
            } else if (o1Field.isJsonNull()) {
                return o2.isJsonNull();
            }
        }
        return false;
    }

    public static int sortFunctionAscending(JsonObject o1, JsonObject o2, String fieldName) {
        final var o1Field = JsonUtils.getFromPath(o1, fieldName);
        final var o2Field = JsonUtils.getFromPath(o2, fieldName);
        if (o1Field == null && o2Field == null) {
            return 0;
        } else if (o1Field == null) {
            return 1;
        } else if (o2Field == null) {
            return -1;
        } else {
            if (o1Field.isJsonPrimitive()) {
                if (o2Field.isJsonPrimitive()) {
                    final var o1Primitive = o1Field.getAsJsonPrimitive();
                    final var o2Primitive = o2Field.getAsJsonPrimitive();
                    if (o1Primitive.isString() && o2Primitive.isString()) {
                        return o1Primitive.getAsString().compareTo(o2Primitive.getAsString());
                    } else if (o1Primitive.isNumber() && o2Primitive.isNumber()) {
                        return Double.compare(o1Primitive.getAsDouble(), o2Primitive.getAsDouble());
                    } else if (o1Primitive.isBoolean() && o2Primitive.isBoolean()) {
                        return o1Primitive.getAsBoolean() ? -1 : 1;
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
        if (o1Field == null && o2Field == null) {
            return 0;
        } else if (o1Field == null) {
            return 1;
        } else if (o2Field == null) {
            return -1;
        } else {
            if (o1Field.isJsonPrimitive()) {
                if (o2Field.isJsonPrimitive()) {
                    final var o1Primitive = o1Field.getAsJsonPrimitive();
                    final var o2Primitive = o2Field.getAsJsonPrimitive();
                    if (o1Primitive.isString() && o2Primitive.isString()) {
                        return o2Primitive.getAsString().compareTo(o1Primitive.getAsString());
                    } else if (o1Primitive.isNumber() && o2Primitive.isNumber()) {
                        return Double.compare(o2Primitive.getAsDouble(), o1Primitive.getAsDouble());
                    } else if (o1Primitive.isBoolean() && o2Primitive.isBoolean()) {
                        return o2Primitive.getAsBoolean() ? 1 : -1;
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
