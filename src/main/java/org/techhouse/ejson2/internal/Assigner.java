package org.techhouse.ejson2.internal;

import org.techhouse.ejson2.elements.*;
import org.techhouse.ejson2.exceptions.MalformedJsonException;
import org.techhouse.ejson2.exceptions.NoConstructorException;

import java.lang.reflect.*;
import java.util.*;

public class Assigner {
    public static <T> T assign(JsonObject parsed, Class<T> tClass) throws Exception {
        final var newInstance = createInstance(tClass, parsed);
        final var fields = getFields(tClass);
        for (var field : fields) {
            final var fieldName = field.getName();
            if (parsed.has(fieldName)) {
                assignValueToField(field, newInstance, parsed.get(fieldName));
            }
        }
        return tClass.cast(newInstance);
    }

    private static <T> void assignValueToField(Field field, T obj, JsonBaseElement parsed) throws Exception {
        final var fieldValue = getFieldValue(field, obj);
        if (fieldValue == null) {
            final var jsonType = parsed.getJsonType();
            final var value = switch (jsonType) {
                case OBJECT -> parsed.asJsonObject();
                case ARRAY -> parsed.asJsonArray();
                case NULL -> null; // Actually this is the value needed
                case DOUBLE -> parsed.asJsonDouble().getValue();
                case STRING -> parsed.asJsonString().getValue();
                case BOOLEAN -> parsed.asJsonBoolean().getValue();
                case SYNTAX -> throw new MalformedJsonException("Shouldn't ever enter here");
            };
            if (value != null) {
                field.setAccessible(true);
                final var casted = cast(field.getType(), parsed);
                field.set(obj, casted);
            }
        }
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
            Object value = valueOf.invoke(null, jsonValue);
            return parameterType.cast(value);
        } else if (parameterType == org.techhouse.ejson.JsonObject.class && jsonType == JsonBaseElement.JsonType.OBJECT) {
            final var valueObject = (JsonObject)jsonValue;
            return (T) toOldJsonObject(valueObject);
        }
        return (T) jsonValue;
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
