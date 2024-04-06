package org.techhouse.ejson2.internal;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.MalformedJsonException;

import java.lang.reflect.*;

public class Assigner {
    public static <T> T assign(JsonObject parsed, Class<T> tClass) throws Exception {
        final var newInstance = ReflectionUtils.createInstance(tClass, parsed);
        final var fields = ReflectionUtils.getFields(tClass);
        for (var field : fields) {
            final var fieldName = field.getName();
            if (parsed.has(fieldName)) {
                assignValueToField(field, newInstance, parsed.get(fieldName));
            }
        }
        return tClass.cast(newInstance);
    }

    private static <T> void assignValueToField(Field field, T obj, JsonBaseElement parsed) throws Exception {
        final var fieldValue = ReflectionUtils.getFieldValue(field, obj);
        if (fieldValue == null) {
            final var jsonType = parsed.getJsonType();
            final var value = switch (jsonType) {
                case OBJECT -> parsed.asJsonObject();
                case ARRAY -> parsed.asJsonArray();
                case NULL -> null; // Actually this is the value needed
                case DOUBLE -> parsed.asJsonDouble().getValue();
                case STRING -> parsed.asJsonString().getValue();
                case BOOLEAN -> parsed.asJsonBoolean().getValue();
                case SYNTAX -> throw new MalformedJsonException("Shouldn't ever enter here");
            };
            if (value != null) {
                final var casted = ReflectionUtils.cast(field.getType(), parsed);
                ReflectionUtils.setFieldValue(field, obj, casted);
            }
        }
    }
}
