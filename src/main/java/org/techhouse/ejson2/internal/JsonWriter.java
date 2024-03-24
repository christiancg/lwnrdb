package org.techhouse.ejson2.internal;

import org.techhouse.ejson2.elements.JsonObject;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.Arrays;

public class JsonWriter {
    public <T> String toJson(T obj) {
        final var result = new StringBuilder();
        result.append('{');
        final var clazz = obj.getClass();
        final var fields = clazz.getDeclaredFields();
        for (var i = 0; i < fields.length; i++) {
            result.append('"');
            result.append(fields[i].getName());
            result.append('"');
            result.append(':');
            try {
                result.append(getFieldValue(fields[i], obj));
            } catch (Exception e) {
                result.append("null");
            }
            if (i < fields.length - 1) {
                result.append(',');
            }
        }
        result.append('}');
        return result.toString();
    }

    private <T> String getFieldValue(Field field, T instance) throws IllegalAccessException, InvocationTargetException {
        Object value = null;
        final var modifiers = field.getModifiers();
        if (Modifier.isPublic(modifiers)) {
            value = field.get(instance);
        } else {
            final var clazz = instance.getClass();
            final var methods = clazz.getMethods();
            final var foundMethod = Arrays.stream(methods).filter(x -> x.getName().equalsIgnoreCase("get" + field.getName()) || x.getName().equalsIgnoreCase(field.getName())).findFirst();
            if (foundMethod.isPresent()) {
                value = foundMethod.get().invoke(instance);
            }
        }
        if (value != null) {
            return switch (value) {
                case String string -> "\"" + string + "\"";
                case Integer integer -> integer.toString();
                case Double d -> d.toString();
                case Enum<?> e -> "\"" + e + "\"";
                case JsonObject jo -> toJson(jo);
                default -> "null";
            };
        } else {
            return "null";
        }
    }
}
