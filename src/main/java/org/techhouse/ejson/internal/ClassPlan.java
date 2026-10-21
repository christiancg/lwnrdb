package org.techhouse.ejson.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import org.techhouse.ejson.elements.JsonObject;
import org.techhouse.ejson.type_adapters.TypeAdapter;
import org.techhouse.ejson.type_adapters.TypeAdapterFactory;

public final class ClassPlan {
    private static final MethodType GETTER_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodType SETTER_TYPE = MethodType.methodType(void.class, Object.class, Object.class);
    private static final ClassValue<ClassPlan> PLANS = new ClassValue<>() {
        @Override
        @SuppressWarnings("NullableProblems")
        protected ClassPlan computeValue(Class<?> type) {
            return build(type);
        }
    };

    private final FieldBinding[] bindings;
    private final Map<String, FieldBinding> byName;
    private final Instantiator instantiator;
    private final boolean record;

    private ClassPlan(FieldBinding[] bindings, Instantiator instantiator, boolean record) {
        this.bindings = bindings;
        this.instantiator = instantiator;
        this.record = record;
        this.byName = new HashMap<>();
        for (final var binding : bindings) {
            byName.putIfAbsent(binding.name(), binding);
        }
    }

    public static ClassPlan of(Class<?> type) {
        return PLANS.get(type);
    }

    public FieldBinding[] bindings() {
        return bindings;
    }

    public FieldBinding bindingNamed(String name) {
        return byName.get(name);
    }

    public boolean isRecord() {
        return record;
    }

    public Object newInstance(JsonObject parsed) throws Exception {
        return instantiator.newInstance(parsed);
    }

    private static ClassPlan build(Class<?> type) {
        try {
            final var fields = ReflectionUtils.getFields(type);
            final var bindings = new FieldBinding[fields.length];
            for (var i = 0; i < fields.length; i++) {
                bindings[i] = bind(fields[i]);
            }
            return new ClassPlan(bindings, Instantiators.forClass(type), type.isRecord());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not plan " + type.getName(), e);
        }
    }

    private static FieldBinding bind(Field field) throws IllegalAccessException {
        final var lookup = MethodHandles.lookup();
        final var getter = lookup.unreflectGetter(field).asType(GETTER_TYPE);
        final var type = field.getType();
        TypeAdapter<Object> adapter = null;
        Exception adapterFailure = null;
        try {
            adapter = adapterFor(field);
        } catch (Exception e) {
            adapterFailure = e;
        }
        final var reader = adapter != null ? ReflectionUtils.readerFor(type, field.getGenericType()) : null;
        return new FieldBinding(field, field.getName(), '"' + field.getName() + "\":", getter, setterFor(lookup, field),
                Modifier.isFinal(field.getModifiers()), adapter, adapterFailure, reader);
    }

    @SuppressWarnings("unchecked")
    private static TypeAdapter<Object> adapterFor(Field field) throws ClassNotFoundException {
        if (field.getType().getTypeParameters().length > 0) {
            return TypeAdapterFactory.getAdapter(field.getGenericType());
        }
        return TypeAdapterFactory.getAdapter((Class<Object>) field.getType());
    }

    private static MethodHandle setterFor(MethodHandles.Lookup lookup, Field field) {
        try {
            return lookup.unreflectSetter(field).asType(SETTER_TYPE);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
