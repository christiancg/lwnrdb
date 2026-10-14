package org.techhouse.ejson.type_adapters.impl;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.config.Globals;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.internal.CharJsonOut;
import org.techhouse.ejson.internal.JsonOut;
import org.techhouse.ejson.internal.ReflectionUtils;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;
import org.techhouse.log.LogRateLimiter;
import org.techhouse.log.Logger;

public class ReflectionTypeAdapter<T> implements TypeAdapter<T> {

    private static final Map<Class<?>, Boolean> genericTypes = new ConcurrentHashMap<>();
    private static final LogRateLimiter DESERIALISATION_FAILURE_LOG = new LogRateLimiter(
            Globals.LOG_RATE_LIMIT_PER_SECOND);

    private final Logger logger = Logger.logFor(ReflectionTypeAdapter.class);
    private final Class<T> clazz;

    private static boolean isGeneric(Class<?> type) {
        final var cached = genericTypes.get(type);
        if (cached != null) {
            return cached;
        }
        final var generic = type.getTypeParameters().length > 0;
        genericTypes.putIfAbsent(type, generic);
        return generic;
    }

    public ReflectionTypeAdapter(Class<T> clazz) {
        if (clazz == null) {
            throw new NullPointerException("clazz can't be null");
        }
        this.clazz = clazz;
    }

    @Override
    public String toJson(T value) {
        final var out = new CharJsonOut();
        toJson(value, out);
        return out.toString();
    }

    @Override
    public void toJson(T value, JsonOut out) {
        out.append('{');
        final var actualClass = value.getClass();
        final var fields = ReflectionUtils.getFields(actualClass);
        for (var i = 0; i < fields.length; i++) {
            out.append('"');
            out.append(fields[i].getName());
            out.append('"');
            out.append(':');
            final var mark = out.mark();
            try {
                appendFieldValue(fields[i], value, out);
            } catch (Exception e) {
                logger.error("Serialising " + actualClass.getName() + "." + fields[i].getName()
                        + " failed; emitting null in its place", e);
                out.reset(mark);
                out.append("null");
            }
            if (i < fields.length - 1) {
                out.append(',');
            }
        }
        out.append('}');
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
            if (clazz.isRecord()) {
                return ReflectionUtils.createRecordInstance(clazz, obj);
            }
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
            final var entry = DESERIALISATION_FAILURE_LOG.admit("Could not read a " + clazz.getSimpleName() + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (entry != null) {
                logger.warning(entry);
            }
            return null;
        }
    }

    private <U> void appendFieldValue(Field field, U instance, JsonOut out)
            throws IllegalAccessException, ClassNotFoundException {
        Object value = ReflectionUtils.getFieldValue(field, instance);
        if (value != null) {
            appendHardCast(value, field.getType(), field, out);
        } else {
            out.append("null");
        }
    }

    private <P> void appendHardCast(Object value, Class<P> pClass, Field field, JsonOut out)
            throws ClassNotFoundException {
        P casted;
        if (field.getType().isPrimitive()) {
            //noinspection unchecked
            casted = (P) value;
        } else {
            casted = pClass.cast(value);
        }
        TypeAdapter<P> adapter;
        if (isGeneric(field.getType())) {
            adapter = TypeAdapterFactory.getAdapter(field.getGenericType());
        } else {
            adapter = TypeAdapterFactory.getAdapter(pClass);
        }
        if (adapter != null) {
            adapter.toJson(casted, out);
        } else {
            logger.error("No type adapter for " + field.getDeclaringClass().getName() + "." + field.getName()
                    + " of type " + pClass.getName() + "; emitting null in its place");
            out.append("null");
        }
    }

    private static <T> void assignValueToField(Field field, T obj, JsonBaseElement parsed) throws Exception {
        final var isFinal = field.accessFlags().contains(java.lang.reflect.AccessFlag.FINAL);
        final var fieldValue = ReflectionUtils.getFieldValue(field, obj);
        // A final field already set by a constructor must not be reassigned; only the UnsafeAllocator
        // path leaves it null.
        if (isFinal && fieldValue != null) {
            return;
        }
        TypeAdapter<?> typeAdapter;
        if (isGeneric(field.getType())) {
            typeAdapter = TypeAdapterFactory.getAdapter(field.getGenericType());
        } else {
            typeAdapter = TypeAdapterFactory.getAdapter(field.getType());
        }
        if (typeAdapter != null) {
            final var casted = ReflectionUtils.cast(field.getType(), parsed, field.getGenericType());
            if (casted != null) {
                ReflectionUtils.setFieldValue(field, obj, casted);
            }
        }
    }
}
