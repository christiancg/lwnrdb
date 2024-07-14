package org.techhouse.ejson.custom_types;

import org.techhouse.ejson.elements.JsonCustom;
import org.techhouse.ejson.elements.JsonString;
import org.techhouse.ejson.exceptions.BadImplementationCustomTypeException;
import org.techhouse.ejson.exceptions.NonRegisteredCustomTypeException;

import java.util.HashMap;
import java.util.Map;

public class CustomTypeFactory {
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
