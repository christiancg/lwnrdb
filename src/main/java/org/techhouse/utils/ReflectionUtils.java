package org.techhouse.utils;

import org.techhouse.ejson.custom_types.CustomTypeFactory;

import java.lang.reflect.ParameterizedType;

public class ReflectionUtils {

    @SuppressWarnings("unchecked cast")
    public abstract static class TypeToken<T> {
        public Class<T> getTypeParameter() {
            final var superclass = this.getClass().getGenericSuperclass();
            if (superclass instanceof ParameterizedType) {
                final var type = ((ParameterizedType) superclass).getActualTypeArguments()[0];
                if (type instanceof Class) {
                    return (Class<T>) type;
                } else {
                    return (Class<T>) ((ParameterizedType)type).getRawType();
                }
            }     // Check for raw TypeToken as superclass
            else if (superclass == TypeToken.class) {
                throw new IllegalStateException(
                        "TypeToken must be created with a type argument: new TypeToken<...>() {}; When using code"
                                + " shrinkers (ProGuard, R8, ...) make sure that generic signatures are preserved.");
            }
            // User created subclass of subclass of TypeToken
            throw new IllegalStateException("Must only create direct subclasses of TypeToken");
        }
    }

    public static Class<?> getClassFromSimpleName(String className) {
        return switch (className) {
            case "String" -> String.class;
            case "Number" -> Number.class;
            case "Boolean" -> Boolean.class;
            default ->  {
                final var customTypes = CustomTypeFactory.getCustomTypes();
                final var customType = customTypes.get(className);
                if (customType != null) {
                    yield customType;
                } else {
                    throw new IllegalStateException("Unexpected value: " + className);
                }
            }
        };
    }
}
