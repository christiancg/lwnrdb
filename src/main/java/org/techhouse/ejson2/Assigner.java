package org.techhouse.ejson2;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.NoConstructorException;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public class Assigner {
    public static <T> T assign(JsonBaseElement parsed, Class<T> tClass) throws InvocationTargetException, IllegalAccessException, InstantiationException {
        final var constructor = getNoArgsConstructor(tClass);
        final var newInstance = constructor.newInstance();
        final var type = parsed.getJsonType();
        final var clazz = newInstance.getClass();
        final var fields = clazz.getDeclaredFields();
        final var methods = clazz.getDeclaredMethods();
        for (var field : fields) {
            final var fieldName = field.getName();
            if (type == JsonBaseElement.JsonType.OBJECT) {
                final var jsonObj = (JsonObject) parsed;
                if (jsonObj.has(fieldName)) {
                    final var fieldValue = jsonObj.get(fieldName);
                    var objField = field.get(newInstance);
                    if (objField == null) {
                        final var objFieldConstructor = getNoArgsConstructor(field.getType());
                        objField = objFieldConstructor.newInstance();
                        field.set(objField, fieldValue);
                    }
                    field.set(newInstance, objField);
                }
            } else if (type == JsonBaseElement.JsonType.ARRAY) {
                final var arr = (JsonArray) parsed;
                for (var item : arr) {
                    // TODO: assign items to collection
                }
            } else {
                findMethodAndAssign(methods, fieldName, newInstance, parsed);
            }
        }
        return tClass.cast(newInstance);
    }

    private static <T> void findMethodAndAssign(Method[] methods, String fieldName, T obj, JsonBaseElement element) throws InvocationTargetException, IllegalAccessException {
        final var foundMethod = Arrays.stream(methods).filter(x -> x.getName().equalsIgnoreCase("set" + fieldName)).findFirst();
        if (foundMethod.isPresent()) {
            var value = switch (element.getJsonType()) {
                case BOOLEAN -> ((JsonBoolean) element).getValue();
                case NULL -> null;
                case STRING -> ((JsonString) element).getValue();
                case DOUBLE -> ((JsonDouble) element).getValue();
                default -> null; // Should never enter here
            };
            foundMethod.get().invoke(obj, value);
        }
    }

    private static <T> Constructor<?> getNoArgsConstructor(Class<T> tClass) {
        final var constructors = tClass.getDeclaredConstructors();
        for (var constructor : constructors) {
            if (constructor.getParameters().length == 0 && constructor.canAccess("")) {
                return constructor;
            }
        }
        throw new NoConstructorException(tClass);
    }
}
