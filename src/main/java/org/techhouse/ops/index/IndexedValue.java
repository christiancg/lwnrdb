package org.techhouse.ops.index;

import org.techhouse.data.FieldIndexEntry;
import org.techhouse.data.IndexKind;
import org.techhouse.ejson.custom_types.CustomTypeFactory;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonBoolean;
import org.techhouse.ejson.elements.JsonNull;
import org.techhouse.ejson.elements.JsonNumber;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.utils.JsonUtils;

public record IndexedValue(Class<?> type, IndexKind kind, String key, Object value) {

    public static IndexedValue of(JsonBaseElement element) {
        if (element == null || element == JsonNull.INSTANCE) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            final var primitive = element.asJsonPrimitive();
            return switch (primitive) {
                case JsonNumber number -> scalar(Number.class, number.doubleValue() + 0.0);
                case JsonString string when string.isJsonCustom() -> {
                    final var custom = CustomTypeFactory.getCustomTypeInstance(string);
                    yield scalar(custom.getClass(), custom);
                }
                case JsonString string -> scalar(String.class, string.getValue());
                case JsonBoolean bool -> scalar(Boolean.class, bool.getValue());
                default -> throw new IllegalStateException("Unexpected value: " + primitive);
            };
        }
        if (element.isJsonObject()) {
            return hashed(IndexKind.OBJECT, JsonUtils.hashElement(element));
        }
        if (element.isJsonArray()) {
            return hashed(IndexKind.ARRAY, JsonUtils.hashElement(element));
        }
        return null;
    }

    private static IndexedValue scalar(Class<?> type, Object value) {
        return new IndexedValue(type, null, FieldIndexEntry.fileKeyOf(value), value);
    }

    private static IndexedValue hashed(IndexKind kind, String hash) {
        return new IndexedValue(null, kind, FieldIndexEntry.fileKeyOf(hash), hash);
    }

    public boolean isHashed() {
        return kind != null;
    }
}
