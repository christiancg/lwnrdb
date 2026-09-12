package org.techhouse.ejson.custom_types;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.BadImplementationCustomTypeException;
import org.techhouse.ejson.exceptions.NonRegisteredCustomTypeException;

public final class CustomTypeFactory {
    private CustomTypeFactory() {
    }
    private static final Map<String, Class<? extends JsonCustom<?>>> _customTypes = new ConcurrentHashMap<>();

    public static void registerCustomType(Class<? extends JsonCustom<?>> aClass) {
        try {
            final var constructor = aClass.getConstructor();
            final var instance = constructor.newInstance();
            _customTypes.put(instance.getCustomTypeName(), aClass);
        } catch (Exception ex) {
            throw new BadImplementationCustomTypeException(aClass.getName(), ex);
        }
    }

    public static Map<String, Class<? extends JsonCustom<?>>> getCustomTypes() {
        return _customTypes;
    }

    public static boolean isKnownCustomOperator(String operatorName) {
        return anyRegisteredType(instance -> instance.customOperatorNames().contains(operatorName)
                || instance.customRankingOperatorNames().contains(operatorName));
    }

    // FILTER picks the top-K path off the name alone, so no type may reuse a predicate operator's name.
    public static boolean isRankingOperator(String operatorName) {
        return anyRegisteredType(instance -> instance.customRankingOperatorNames().contains(operatorName));
    }

    private static boolean anyRegisteredType(Predicate<JsonCustom<?>> predicate) {
        for (var aClass : _customTypes.values()) {
            try {
                if (predicate.test(aClass.getConstructor().newInstance())) {
                    return true;
                }
            } catch (Exception ex) {
                throw new BadImplementationCustomTypeException(aClass.getName(), ex);
            }
        }
        return false;
    }

    public static JsonCustom<?> getCustomTypeInstance(JsonString strElement) {
        final var toParse = strElement.getValue();
        return getCustomTypeInstance(toParse);
    }

    public static JsonCustom<?> getCustomTypeInstance(String toParse) {
        final var typeName = toParse.substring(1, toParse.indexOf('('));
        final var customType = _customTypes.get(typeName);
        if (customType == null) {
            throw new NonRegisteredCustomTypeException(typeName);
        } else {
            try {
                final var constructor = customType.getConstructor(String.class);
                return constructor.newInstance(toParse);
            } catch (Exception ex) {
                throw new BadImplementationCustomTypeException(customType.getName(), ex);
            }
        }
    }
}
