package org.techhouse.ejson.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.exceptions.NoConstructorException;

final class Instantiators {
    private record FinalFieldConstructor(MethodHandle constructor, String[] fieldNames, ValueReader[] readers) {
        Object tryNewInstance(JsonObject parsed) throws Exception {
            final var arguments = new Object[fieldNames.length];
            for (var i = 0; i < fieldNames.length; i++) {
                if (!parsed.has(fieldNames[i])) {
                    return null;
                }
                arguments[i] = readers[i].read(parsed.get(fieldNames[i]));
            }
            return HandleInvoker.spread(constructor, arguments);
        }
    }

    private Instantiators() {
    }

    static Instantiator forClass(Class<?> type) throws ReflectiveOperationException {
        if (type.isRecord()) {
            return forRecord(type);
        }
        final var constructors = ReflectionUtils.getConstructors(type);
        for (final var constructor : constructors) {
            if (constructor.getParameterCount() == 0 && Modifier.isPublic(constructor.getModifiers())) {
                return noArgument(constructor);
            }
        }
        return matchingFinalFields(type, constructors);
    }

    private static Instantiator noArgument(Constructor<?> constructor) throws IllegalAccessException {
        constructor.setAccessible(true);
        final var handle = MethodHandles.lookup().unreflectConstructor(constructor)
                .asType(MethodType.methodType(Object.class));
        return _ -> HandleInvoker.supply(handle);
    }

    private static Instantiator matchingFinalFields(Class<?> type, Constructor<?>[] constructors)
            throws IllegalAccessException {
        final var finalFields = new ArrayList<Field>();
        for (final var field : ReflectionUtils.getFields(type)) {
            if (Modifier.isFinal(field.getModifiers())) {
                finalFields.add(field);
            }
        }
        final var candidates = new ArrayList<FinalFieldConstructor>();
        for (final var constructor : constructors) {
            if (parameterTypesMatch(constructor, finalFields)) {
                candidates.add(finalFieldConstructor(constructor, finalFields));
            }
        }
        return parsed -> {
            for (final var candidate : candidates) {
                final var instance = candidate.tryNewInstance(parsed);
                if (instance != null) {
                    return instance;
                }
            }
            return allocated(type);
        };
    }

    private static boolean parameterTypesMatch(Constructor<?> constructor, List<Field> finalFields) {
        final var parameterTypes = constructor.getParameterTypes();
        if (parameterTypes.length != finalFields.size()) {
            return false;
        }
        for (var i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] != finalFields.get(i).getType()) {
                return false;
            }
        }
        return true;
    }

    private static FinalFieldConstructor finalFieldConstructor(Constructor<?> constructor, List<Field> finalFields)
            throws IllegalAccessException {
        constructor.setAccessible(true);
        final var names = new String[finalFields.size()];
        final var readers = new ValueReader[finalFields.size()];
        for (var i = 0; i < names.length; i++) {
            names[i] = finalFields.get(i).getName();
            readers[i] = ReflectionUtils.readerFor(finalFields.get(i).getType(), null);
        }
        return new FinalFieldConstructor(spreader(constructor), names, readers);
    }

    private static Object allocated(Class<?> type) {
        try {
            return UnsafeAllocator.INSTANCE.newInstance(type);
        } catch (Exception e) {
            throw new NoConstructorException(type);
        }
    }

    private static Instantiator forRecord(Class<?> type) throws ReflectiveOperationException {
        final var components = type.getRecordComponents();
        final var componentTypes = new Class<?>[components.length];
        final var names = new String[components.length];
        final var readers = new ValueReader[components.length];
        for (var i = 0; i < components.length; i++) {
            componentTypes[i] = components[i].getType();
            names[i] = components[i].getName();
            readers[i] = ReflectionUtils.readerFor(componentTypes[i], components[i].getGenericType());
        }
        final var constructor = type.getDeclaredConstructor(componentTypes);
        constructor.setAccessible(true);
        final var canonical = spreader(constructor);
        return parsed -> {
            final var arguments = new Object[names.length];
            for (var i = 0; i < names.length; i++) {
                final var value = parsed.has(names[i]) ? readers[i].read(parsed.get(names[i])) : null;
                arguments[i] = value == null ? defaultComponentValue(componentTypes[i]) : value;
            }
            return HandleInvoker.spread(canonical, arguments);
        };
    }

    private static MethodHandle spreader(Constructor<?> constructor) throws IllegalAccessException {
        return MethodHandles.lookup().unreflectConstructor(constructor)
                .asSpreader(Object[].class, constructor.getParameterCount())
                .asType(MethodType.methodType(Object.class, Object[].class));
    }

    private static Object defaultComponentValue(Class<?> componentType) {
        if (!componentType.isPrimitive()) {
            return null;
        }
        return switch (componentType.getName()) {
            case "boolean" -> false;
            case "char" -> '\0';
            case "byte" -> (byte) 0;
            case "short" -> (short) 0;
            case "int" -> 0;
            case "long" -> 0L;
            case "float" -> 0.0f;
            default -> 0.0d;
        };
    }
}
