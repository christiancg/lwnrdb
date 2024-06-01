package org.techhouse.ejson2.type_adapters;

import org.techhouse.ejson2.type_adapters.impl.EnumTypeAdapter;
import org.techhouse.ejson2.type_adapters.impl.IterableTypeAdapter;
import org.techhouse.ejson2.type_adapters.impl.ReflectionTypeAdapter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class TypeAdapterFactory {
    private static final Map<Class<?>, TypeAdapter<?>> _adapters = new HashMap<>();
    private static final Map<Type, TypeAdapter<?>> _genericTypeAdapters = new HashMap<>();

    public static void registerTypeAdapter(Class<?> type, TypeAdapter<?> adapter) {
        _adapters.put(type, adapter);
    }

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

    public static <T> TypeAdapter<T> getAdapter(Class<T> type) {
        final var adapter = _adapters.get(type);
        if (adapter != null) {
            return (TypeAdapter<T>) adapter; // Cast should always work
        } else if (type.isEnum()) {
            final var enumAdapter = new EnumTypeAdapter(type); // this is a raw usage but should be safe as type is an enum
            _adapters.put(type, enumAdapter);
            return enumAdapter;
        } else {
            final var newAdapter = new ReflectionTypeAdapter<>(type);
            _adapters.put(type, newAdapter);
            return newAdapter;
        }
    }
}
