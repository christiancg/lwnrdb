package org.techhouse.utils;

import org.techhouse.ejson.custom_types.CustomTypeFactory;

public class ReflectionUtils {
    public static Class<?> getClassFromSimpleName(String className) {
        return switch (className) {
            case "String" -> String.class;
            case "Double" -> Double.class;
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
