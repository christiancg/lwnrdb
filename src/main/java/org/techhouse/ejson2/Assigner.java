package org.techhouse.ejson2;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.NoConstructorException;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Assigner {
    public static <T> T assign(JsonBaseElement parsed, Class<T> tClass) throws Exception {
        final var newInstance = createInstance(tClass, parsed);
        final var type = parsed.getJsonType();
        final var fields = getFields(tClass);
        final var methods = tClass.getDeclaredMethods();
        for (var field : fields) {
            final var fieldValue = getFieldValue(field, newInstance);
            if (fieldValue == null) {
                final var fieldType = field.getType();
                final var fieldName = field.getName();
                if (type == JsonBaseElement.JsonType.OBJECT) {
                    final var jsonObj = (JsonObject) parsed;
                    if (jsonObj.has(fieldName)) {
                        final var jsonObjFieldValue = jsonObj.get(fieldName);
                        var objField = getFieldValue(field, newInstance);
                        if (objField == null) {
                            if (isSameType(field.getType(), jsonObjFieldValue)) {
                                objField = jsonObjFieldValue;
                            } else {
                                objField = assign(jsonObjFieldValue, fieldType);
                            }
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
        }
        return tClass.cast(newInstance);
    }

    private static <T, U> boolean isSameType(Class<T> obj1, U obj2) {
        return obj1.equals(obj2.getClass());
    }

    private static <T> Field[] getFields(Class<T> tClass) {
        final List<Field> fields = new ArrayList<>(List.of(tClass.getDeclaredFields()));
        final var superClass = tClass.getSuperclass();
        if (!superClass.equals(Object.class)) {
            fields.addAll(List.of(getFields(superClass)));
        }
        return fields.toArray(Field[]::new);
    }

    private static <T> Object getFieldValue(Field field, T instance) throws IllegalAccessException, InvocationTargetException {
        final var modifiers = field.getModifiers();
        if (Modifier.isPublic(modifiers)) {
            return field.get(instance);
        } else {
            final var clazz = instance.getClass();
            final var methods = clazz.getMethods();
            final var foundMethod = Arrays.stream(methods).filter(x -> x.getName().equalsIgnoreCase("get" + field.getName()) || x.getName().equalsIgnoreCase(field.getName())).findFirst();
            if (foundMethod.isPresent()) {
                return foundMethod.get().invoke(instance);
            }
        }
        return null;
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

    private static <T> T createInstance(Class<T> tClass, JsonBaseElement parsed) throws Exception {
        final var constructors = tClass.getDeclaredConstructors();
        for (var constructor : constructors) {
            final var constructorModifiers = constructor.getModifiers();
            if (constructor.getParameters().length == 0 && Modifier.isPublic(constructorModifiers)) {
                return tClass.cast(constructor.newInstance());
            }
        }
        final var fields = tClass.getDeclaredFields();
        final var finalFields = new ArrayList<Field>();
        for (var field : fields) {
            if (field.accessFlags().contains(AccessFlag.FINAL)) {
                finalFields.add(field);
            }
        }
        final var parsedObject = (JsonObject) parsed;
        for (var constructor : constructors) {
            final var parameters = constructor.getParameters();
            if (parameters.length == finalFields.size()) {
                final var parameterValues = new Object[parameters.length];
                for (int i = 0; i < parameters.length; i++) {
                    final var parameter = parameters[i];
                    final var finalField = finalFields.get(i);
                    if (parameter.getType() != finalField.getType() || !parsedObject.has(finalField.getName())) {
                        break;
                    } else {
                        final var fieldValue = parsedObject.get(finalField.getName());
                        final var actualValue = cast(parameter.getType(), fieldValue);
                        parameterValues[i] = actualValue;
                    }
                }
                return tClass.cast(constructor.newInstance(parameterValues));
            }
        }
        try {
            return UnsafeAllocator.INSTANCE.newInstance(tClass);
        } catch (Exception e) {
            throw new NoConstructorException(tClass);
        }
    }

    private static <T> T cast(Class<T> parameterType, JsonBaseElement fieldValue) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        final var jsonValue = switch (fieldValue.getJsonType()) {
            case ARRAY -> null; // TODO: implement array init here
            case OBJECT -> null; // TODO: implement object init here
            case BOOLEAN -> ((JsonBoolean) fieldValue).getValue();
            case NULL -> null;
            case STRING -> ((JsonString) fieldValue).getValue();
            case DOUBLE -> ((JsonDouble) fieldValue).getValue();
            case SYNTAX -> null; // should never come here
        };
        if (parameterType.isEnum() && jsonValue instanceof String) {
            Method valueOf = parameterType.getMethod("valueOf", String.class);
            Object value = valueOf.invoke(null, jsonValue);
            return parameterType.cast(value);
        } else if (parameterType.equals(String.class)) {
            return (T) jsonValue;
        }
        return null;
    }
}
