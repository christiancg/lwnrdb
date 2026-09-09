package org.techhouse.data;

import org.techhouse.ejson.elements.JsonObject;

public final class JsonFieldReader {
    private JsonFieldReader() {
    }

    public static String stringOrNull(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return null;
        }
        return object.get(field).asJsonString().getValue();
    }

    public static long longOrZero(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return 0L;
        }
        return object.get(field).asJsonNumber().getValue().longValue();
    }

    public static boolean booleanOrDefault(JsonObject object, String field, boolean fallback) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return fallback;
        }
        return object.get(field).asJsonBoolean().getValue();
    }
}
