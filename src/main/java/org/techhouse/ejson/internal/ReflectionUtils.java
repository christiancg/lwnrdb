package org.techhouse.ejson.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public class ReflectionUtils {
    private record ClassSpecification(Constructor<?>[] constructors, Field[] fields) {
    }

    private static final Map<Class<?>, ClassSpecification> classSpecifications = new ConcurrentHashMap<>();

    public static <T> Object getFieldValue(Field field, T instance) throws IllegalAccessException {
        return field.get(instance);
    }

    public static <T> void setFieldValue(Field field, T instance, Object value) throws IllegalAccessException {
        field.set(instance, value);
    }

    public static <T> Field[] getFields(Class<T> tClass) {
        return specificationFor(tClass).fields();
    }

    public static <T> Constructor<?>[] getConstructors(Class<T> tClass) {
        return specificationFor(tClass).constructors();
    }

    private static <T> ClassSpecification specificationFor(Class<T> tClass) {
        final var existing = classSpecifications.get(tClass);
        if (existing != null) {
            return existing;
        }
        final var specification = new ClassSpecification(internalGetConstructors(tClass), internalGetFields(tClass));
        final var raced = classSpecifications.putIfAbsent(tClass, specification);
        return raced != null ? raced : specification;
    }

    // Static fields are skipped: including them would put every `public static final` on the wire and
    // then try to assign them back from the document. Transient fields are node-local and never cross it.
    private static <T> Field[] internalGetFields(Class<T> tClass) {
        final List<Field> fields = new ArrayList<>();
        for (final var field : tClass.getDeclaredFields()) {
            final var modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) && !Modifier.isTransient(modifiers)) {
                field.setAccessible(true);
                fields.add(field);
            }
        }
        final var superClass = tClass.getSuperclass();
        if (!superClass.equals(Object.class) && !superClass.equals(Record.class)) {
            fields.addAll(List.of(getFields(superClass)));
        }
        return fields.toArray(Field[]::new);
    }

    private static <T> Constructor<?>[] internalGetConstructors(Class<T> tClass) {
        return tClass.getDeclaredConstructors();
    }

    @SuppressWarnings("unchecked")
    public static <T> T cast(Class<T> parameterType, JsonBaseElement fieldValue, Type genericType) throws Exception {
        return (T) readerFor(parameterType, genericType).read(fieldValue);
    }

    public static ValueReader readerFor(Class<?> parameterType, Type genericType) {
        final var enumValueOf = parameterType.isEnum() ? enumValueOf(parameterType) : null;
        final var listReader = genericType != null && parameterType.isAssignableFrom(List.class)
                ? listReaderFor(genericType)
                : null;
        final var narrowsNumber = Number.class.isAssignableFrom(parameterType);
        final var primitive = parameterType.isPrimitive();
        return fieldValue -> {
            final var jsonType = fieldValue.getJsonType();
            if (jsonType == JsonBaseElement.JsonType.NULL) {
                return null;
            }
            final var jsonValue = switch (jsonType) {
                case ARRAY -> fieldValue.asJsonArray();
                case OBJECT -> fieldValue.asJsonObject();
                case BOOLEAN -> fieldValue.asJsonBoolean().getValue();
                case STRING, CUSTOM -> fieldValue.asJsonString().getValue();
                case NUMBER -> fieldValue.asJsonNumber().getValue();
                case SYNTAX -> null;
                default -> throw new IllegalStateException("Unexpected value: " + jsonType);
            };
            if (enumValueOf != null && jsonValue instanceof String name) {
                return parameterType.cast(HandleInvoker.apply(enumValueOf, name));
            } else if (listReader != null) {
                return listReader.read(fieldValue);
            } else if (jsonValue != null && !parameterType.isAssignableFrom(jsonValue.getClass()) && narrowsNumber) {
                final var narrowed = narrowNumber(parameterType, jsonValue);
                if (narrowed != null) {
                    return narrowed;
                }
            } else if (primitive) {
                return toPrimitiveValue(parameterType, jsonValue);
            } else if (jsonValue != null && fieldValue.isJsonObject()) {
                return TypeAdapterFactory.getAdapter(parameterType).fromJson(fieldValue);
            }
            return parameterType.cast(jsonValue);
        };
    }

    private static Number narrowNumber(Class<?> parameterType, Object value) {
        if (!(value instanceof Number jsonValue)) {
            throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to java.lang.Number");
        }
        if (parameterType == Integer.class) {
            return jsonValue.intValue();
        } else if (parameterType == Double.class) {
            return jsonValue.doubleValue();
        } else if (parameterType == Float.class) {
            return jsonValue.floatValue();
        } else if (parameterType == Long.class) {
            return jsonValue.longValue();
        }
        return null;
    }

    private static MethodHandle enumValueOf(Class<?> enumType) {
        try {
            final var valueOf = enumType.getMethod("valueOf", String.class);
            valueOf.setAccessible(true);
            return MethodHandles.lookup().unreflect(valueOf).asType(MethodType.methodType(Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ValueReader listReaderFor(Type genericType) {
        try {
            final var typeArguments = ((ParameterizedType) genericType).getActualTypeArguments();
            if (typeArguments.length != 1) {
                return _ -> null;
            }
            final var itemAdapter = TypeAdapterFactory.getAdapter(Class.forName(typeArguments[0].getTypeName()));
            return fieldValue -> {
                final var items = new ArrayList<>();
                for (final var item : fieldValue.asJsonArray()) {
                    items.add(itemAdapter.fromJson(item));
                }
                return items;
            };
        } catch (ClassCastException | ClassNotFoundException e) {
            return _ -> {
                throw e;
            };
        }
    }

    // Reflective field set only unboxes, so the box must already match the field's type: a double-valued
    // number reaching a long field has to be narrowed here.
    private static Object toPrimitiveValue(Class<?> parameterType, Object jsonValue) {
        if (!(jsonValue instanceof Number number)) {
            return jsonValue;
        }
        return switch (parameterType.getName()) {
            case "int" -> number.intValue();
            case "long" -> number.longValue();
            case "double" -> number.doubleValue();
            case "float" -> number.floatValue();
            case "short" -> number.shortValue();
            case "byte" -> number.byteValue();
            default -> jsonValue;
        };
    }
}
