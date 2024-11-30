package org.techhouse.ejson.internal;

import org.techhouse.ejson.elements.*;
import org.techhouse.ejson.exceptions.NoConstructorException;

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
                    constructor.setAccessible(true);
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
            case STRING, CUSTOM -> fieldValue.asJsonString().getValue();
            case NUMBER -> fieldValue.asJsonNumber().getValue();
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
        } else if (jsonValue != null && !parameterType.isAssignableFrom(jsonValue.getClass()) && Number.class.isAssignableFrom(parameterType)) {
            final var numberClass = Number.class;
            if (parameterType == Integer.class) {
                return parameterType.cast(numberClass.cast(jsonValue).intValue());
            } else if (parameterType == Double.class) {
                return parameterType.cast(numberClass.cast(jsonValue).doubleValue());
            } else if (parameterType == Float.class) {
                return parameterType.cast(numberClass.cast(jsonValue).floatValue());
            } else if (parameterType == Long.class) {
                return parameterType.cast(numberClass.cast(jsonValue).longValue());
            }
        } else if (parameterType.isPrimitive()) {
            return (T)jsonValue;
        }
        return parameterType.cast(jsonValue);
    }
}
