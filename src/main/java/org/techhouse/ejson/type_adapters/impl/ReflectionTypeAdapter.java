package org.techhouse.ejson.type_adapters.impl;

import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.ReflectionUtils;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

import java.lang.reflect.Field;

public class ReflectionTypeAdapter<T> implements TypeAdapter<T> {

    private final Class<T> clazz;

    public ReflectionTypeAdapter(Class<T> clazz) {
        if (clazz == null) {
            throw new NullPointerException("clazz can't be null");
        }
        this.clazz = clazz;
    }

    @Override
    public String toJson(T value) {
        final var result = new StringBuilder();
        result.append('{');
        final var clazz = value.getClass();
        final var fields = ReflectionUtils.getFields(clazz);
        for (var i = 0; i < fields.length; i++) {
            result.append('"');
            result.append(fields[i].getName());
            result.append('"');
            result.append(':');
            try {
                result.append(getFieldValue(fields[i], value));
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

    @Override
    public T fromJson(JsonBaseElement value) {
        JsonObject obj;
        if (value.getJsonType() == JsonBaseElement.JsonType.OBJECT) {
            obj = (JsonObject) value;
        } else {
            return null;
        }
        try {
            final var instance = ReflectionUtils.createInstance(clazz, obj);
            final var fields = ReflectionUtils.getFields(clazz);
            for (var field : fields) {
                final var fieldName = field.getName();
                if (obj.has(fieldName)) {
                    assignValueToField(field, instance, obj.get(fieldName));
                }
            }
            return clazz.cast(instance);
        } catch (Exception e) {
            return null;
        }
    }

    private <U> String getFieldValue(Field field, U instance) throws IllegalAccessException, ClassNotFoundException {
        Object value = ReflectionUtils.getFieldValue(field, instance);
        if (value != null) {
            final var pClass = field.getType();
            return hardCast(value, pClass, field); // This should be fine
        } else {
            return "null";
        }
    }

    private <P> String hardCast(Object value, Class<P> pClass, Field field) throws ClassNotFoundException {
        P casted;
        if (field.getType().isPrimitive()) {
            casted = (P) value;
        } else {
            casted = pClass.cast(value);
        }
        TypeAdapter<P> adapter;
        if (field.getType().getTypeParameters().length > 0) {
            adapter = TypeAdapterFactory.getAdapter(field.getGenericType());
        } else {
            adapter = TypeAdapterFactory.getAdapter(pClass);
        }
        if (adapter != null) {
            return adapter.toJson(casted);
        } else {
            return null;
        }
    }

    private static <T> void assignValueToField(Field field, T obj, JsonBaseElement parsed) throws Exception {
        final var fieldValue = ReflectionUtils.getFieldValue(field, obj);
        if (fieldValue == null || field.getType().isPrimitive()) {
            TypeAdapter<?> typeAdapter;
            if (field.getType().getTypeParameters().length > 0) {
                typeAdapter = TypeAdapterFactory.getAdapter(field.getGenericType());
            } else {
                typeAdapter = TypeAdapterFactory.getAdapter(field.getType());
            }
            if (typeAdapter != null) {
                final var value = typeAdapter.fromJson(parsed);
                if (value != null) {
                    final var casted = ReflectionUtils.cast(field.getType(), parsed, field.getGenericType());
                    ReflectionUtils.setFieldValue(field, obj, casted);
                }
            }
        }
    }
}
