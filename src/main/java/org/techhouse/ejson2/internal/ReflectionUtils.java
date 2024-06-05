package org.techhouse.ejson2.internal;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.NoConstructorException;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ReflectionUtils {
    private record ClassSpecification(Constructor<?>[] constructors, Field[] fields) {
    }

    private static final HashMap<Class<?>, ClassSpecification> classSpecifications = new HashMap<>();

    public static <T> Object getFieldValue(Field field, T instance) throws IllegalAccessException {
        return field.get(instance);
    }

    public static <T> void setFieldValue(Field field, T instance, Object value) throws IllegalAccessException {
        field.set(instance, value);
    }

    public static <T> Field[] getFields(Class<T> tClass) {
        if (classSpecifications.containsKey(tClass)) {
            return classSpecifications.get(tClass).fields();
        } else {
            final var constructorArr = internalGetConstructors(tClass);
            final var fieldArr = internalGetFields(tClass);
            classSpecifications.put(tClass, new ClassSpecification(constructorArr, fieldArr));
            return fieldArr;
        }
    }

    public static <T> Constructor<?>[] getConstructors(Class<T> tClass) {
        if (classSpecifications.containsKey(tClass)) {
            return classSpecifications.get(tClass).constructors();
        } else {
            final var constructorArr = internalGetConstructors(tClass);
            final var fieldArr = internalGetFields(tClass);
            classSpecifications.put(tClass, new ClassSpecification(constructorArr, fieldArr));
            return constructorArr;
        }
    }

    private static <T> Field[] internalGetFields(Class<T> tClass) {
        final var fieldList = List.of(tClass.getDeclaredFields());
        fieldList.forEach(field -> field.setAccessible(true));
        final List<Field> fields = new ArrayList<>(fieldList);
        final var superClass = tClass.getSuperclass();
        if (!superClass.equals(Object.class)) {
            fields.addAll(List.of(getFields(superClass)));
        }
        return fields.toArray(Field[]::new);
    }

    private static <T> Constructor<?>[] internalGetConstructors(Class<T> tClass) {
        return tClass.getDeclaredConstructors();
    }

    public static <T> T createInstance(Class<T> tClass, JsonBaseElement parsed) throws Exception {
        final var constructors = getConstructors(tClass);
        for (var constructor : constructors) {
            final var constructorModifiers = constructor.getModifiers();
            if (constructor.getParameters().length == 0 && Modifier.isPublic(constructorModifiers)) {
                return tClass.cast(constructor.newInstance());
            }
        }
        final var fields = getFields(tClass);
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
                var shouldContinue = true;
                for (int i = 0; i < parameters.length; i++) {
                    final var parameter = parameters[i];
                    final var finalField = finalFields.get(i);
                    if (parameter.getType() != finalField.getType() || !parsedObject.has(finalField.getName())) {
                        shouldContinue = false;
                        break;
                    } else {
                        final var fieldValue = parsedObject.get(finalField.getName());
                        final var actualValue = cast(parameter.getType(), fieldValue, null);
                        parameterValues[i] = actualValue;
                    }
                }
                if (shouldContinue) {
                    return tClass.cast(constructor.newInstance(parameterValues));
                }
            }
        }
        try {
            return UnsafeAllocator.INSTANCE.newInstance(tClass);
        } catch (Exception e) {
            throw new NoConstructorException(tClass);
        }
    }

    public static <T> T cast(Class<T> parameterType, JsonBaseElement fieldValue, Type genericType) throws Exception {
        final var jsonType = fieldValue.getJsonType();
        final var jsonValue = switch (jsonType) {
            case ARRAY -> fieldValue.asJsonArray();
            case OBJECT -> fieldValue.asJsonObject();
            case BOOLEAN -> fieldValue.asJsonBoolean().getValue();
            case NULL -> null;
            case STRING -> fieldValue.asJsonString().getValue();
            case DOUBLE -> fieldValue.asJsonDouble().getValue();
            case SYNTAX -> null; // should never come here
        };
        if (parameterType.isEnum() && jsonValue instanceof String) {
            Method valueOf = parameterType.getMethod("valueOf", String.class);
            Object value = valueOf.invoke(null, jsonValue.toString());
            return parameterType.cast(value);
        } else if (genericType != null && parameterType.isAssignableFrom(List.class)) {
            final var typeArguments = ((ParameterizedType)genericType).getActualTypeArguments();
            if (typeArguments.length == 1) {
                final var actualType = typeArguments[0];
                final var actualClass = Class.forName(actualType.getTypeName());
                List<Object> listInstance = new ArrayList<>();
                fieldValue.asJsonArray().forEach((arrItem) -> {
                    final var obj = arrItem.asJsonObject();
                    final var item = Assigner.assign(obj, actualClass);
                    listInstance.add(item);
                });
                return parameterType.cast(listInstance);
            }
            return null;
        } else if (parameterType == org.techhouse.ejson.JsonObject.class && jsonType == JsonBaseElement.JsonType.OBJECT) {
            // TODO: remove this as it is just for having both EJson libraries
            final var valueObject = (JsonObject)jsonValue;
            return parameterType.cast(toOldJsonObject(valueObject));
        } else if (jsonValue != null && !parameterType.isAssignableFrom(jsonValue.getClass()) && Number.class.isAssignableFrom(parameterType)) {
            if (parameterType == Integer.class) {
                return parameterType.cast(((Number)jsonValue).intValue());
            } else if (parameterType == Double.class) {
                return parameterType.cast(((Number)jsonValue).doubleValue());
            } else if (parameterType == Float.class) {
                return parameterType.cast(((Number)jsonValue).floatValue());
            } else if (parameterType == Long.class) {
                return parameterType.cast(((Number)jsonValue).longValue());
            }
        }
        return parameterType.cast(jsonValue);
    }

    // TODO: remove this as it is just for testing
    private static org.techhouse.ejson.JsonObject toOldJsonObject(JsonObject jsonObject) {
        final var oldObject = new org.techhouse.ejson.JsonObject();
        for (var field : jsonObject.entrySet()) {
            oldObject.add(field.getKey(), toJsonElement(field.getValue()));
        }
        return oldObject;
    }

    // TODO: remove this as it is just for testing
    private static org.techhouse.ejson.JsonElement toJsonElement(JsonBaseElement jsonBaseElement) {
        return switch (jsonBaseElement) {
            case JsonBoolean jsonBoolean -> new org.techhouse.ejson.JsonPrimitive(jsonBoolean.getValue());
            case JsonDouble jsonDouble -> new org.techhouse.ejson.JsonPrimitive(jsonDouble.getValue());
            case JsonNull ignored -> org.techhouse.ejson.JsonNull.INSTANCE;
            case JsonString jsonString -> new org.techhouse.ejson.JsonPrimitive(jsonString.getValue());
            case JsonArray jsonArray -> {
                final var arr = new org.techhouse.ejson.JsonArray();
                for (var item : jsonArray) {
                    arr.add(toJsonElement(item));
                }
                yield arr;
            }
            case JsonObject jsonObject1 -> toOldJsonObject(jsonObject1);
            default -> null;
        };
    }
}
