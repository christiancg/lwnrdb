package org.techhouse.ejson.custom_types;

import java.util.HashMap;
import java.util.Map;
import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.BadImplementationCustomTypeException;
import org.techhouse.ejson.exceptions.NonRegisteredCustomTypeException;

public final class CustomTypeFactory {
    private CustomTypeFactory() {
    }
    private static final Map<String, Class<? extends JsonCustom<?>>> _customTypes = new HashMap<>();

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

    // True when some registered custom type declares a predicate or ranking operator with this name.
    // Used to validate CUSTOM operators in requests.
    public static boolean isKnownCustomOperator(String operatorName) {
        for (var aClass : _customTypes.values()) {
            try {
                final var instance = aClass.getConstructor().newInstance();
                if (instance.customOperatorNames().contains(operatorName)
                        || instance.customRankingOperatorNames().contains(operatorName)) {
                    return true;
                }
            } catch (Exception ex) {
                throw new BadImplementationCustomTypeException(aClass.getName(), ex);
            }
        }
        return false;
    }

    // True when the operator name is a ranking (top-K) operator; the FILTER step routes these to its
    // score-and-keep-top-K path instead of the predicate path.
    public static boolean isRankingOperator(String operatorName) {
        for (var aClass : _customTypes.values()) {
            try {
                final var instance = aClass.getConstructor().newInstance();
                if (instance.customRankingOperatorNames().contains(operatorName)) {
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
