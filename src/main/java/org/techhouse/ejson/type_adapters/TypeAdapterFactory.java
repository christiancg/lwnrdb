package org.techhouse.ejson.type_adapters;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.type_adapters.impl.EnumTypeAdapter;
import org.techhouse.ejson.type_adapters.impl.IterableTypeAdapter;
import org.techhouse.ejson.type_adapters.impl.JsonCustomTypeAdapter;
import org.techhouse.ejson.type_adapters.impl.ReflectionTypeAdapter;

public final class TypeAdapterFactory {
    private TypeAdapterFactory() {
    }

    private static final Map<Class<?>, TypeAdapter<?>> _adapters = new ConcurrentHashMap<>();
    private static final Map<Type, TypeAdapter<?>> _genericTypeAdapters = new ConcurrentHashMap<>();

    public static void registerTypeAdapter(Class<?> type, TypeAdapter<?> adapter) {
        _adapters.put(type, adapter);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> TypeAdapter<T> getAdapter(Type type) throws ClassNotFoundException {
        final var adapter = _genericTypeAdapters.get(type);
        if (adapter != null) {
            return (TypeAdapter<T>) adapter;
        } else {
            final var parameterizedType = (ParameterizedType) type;
            final var clazz = (Class<?>) parameterizedType.getRawType();
            if (Iterable.class.isAssignableFrom(clazz)) {
                final var typeArgument1 = parameterizedType.getActualTypeArguments()[0];
                final var typeArgument1Name = typeArgument1.getTypeName();
                final var iterableAdapter = new IterableTypeAdapter(Class.forName(typeArgument1Name));
                _genericTypeAdapters.put(type, iterableAdapter);
                return iterableAdapter;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static <T> TypeAdapter<T> getAdapter(Class<T> type) {
        return (TypeAdapter<T>) _adapters.computeIfAbsent(type, TypeAdapterFactory::createAdapter);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeAdapter<?> createAdapter(Class<?> type) {
        if (type.isEnum()) {
            return new EnumTypeAdapter(type);
        }
        if (JsonCustom.class.isAssignableFrom(type)) {
            return new JsonCustomTypeAdapter();
        }
        return new ReflectionTypeAdapter<>(type);
    }
}
