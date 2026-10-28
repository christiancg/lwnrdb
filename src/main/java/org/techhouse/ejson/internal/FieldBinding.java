package org.techhouse.ejson.internal;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import org.techhouse.ejson.elements.JsonBaseElement;
import org.techhouse.ejson.type_adapters.TypeAdapter;

public record FieldBinding(Field field, String name, String nameToken, MethodHandle getter, MethodHandle setter,
        boolean isFinal, TypeAdapter<Object> adapter, Exception adapterFailure, ValueReader reader) {

    public Object get(Object instance) throws Exception {
        return HandleInvoker.apply(getter(), instance);
    }

    public void requireAdapter() throws Exception {
        if (adapterFailure() != null) {
            throw adapterFailure();
        }
    }

    public void assign(Object instance, JsonBaseElement parsed) throws Exception {
        if (isFinal() && get(instance) != null) {
            return;
        }
        requireAdapter();
        if (adapter() == null) {
            return;
        }
        final var value = reader().read(parsed);
        if (value != null) {
            set(instance, value);
        }
    }

    private void set(Object instance, Object value) throws Exception {
        if (setter() == null) {
            throw new IllegalAccessException("Can not set " + field().getDeclaringClass().getName() + "." + name());
        }
        HandleInvoker.accept(setter(), instance, value);
    }
}
