package org.techhouse.simplejs.values;

import java.util.List;
import org.techhouse.simplejs.builtins.FunctionProtoBuiltins;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;

// ValidateAndApplyPropertyDescriptor and its neighbours, shared by every value type that carries a
// PropertyTable. A Slot is one key's storage, so string keys and symbol keys run the same algorithm;
// an exotic type's own arms (array indices, the global Environment) are handled before this.
public final class OrdinaryProperties {
    private OrdinaryProperties() {
    }

    interface Slot {
        boolean exists();

        boolean hasAccessor();

        PropertyFlags flags();

        void setFlags(PropertyFlags flags);

        JsValue value();

        boolean hasValue();

        void defineValue(JsValue value);

        void removeValue();

        JsValue getter();

        JsValue setter();

        void defineAccessor(JsValue getter, JsValue setter);

        void clearGetter();

        void clearSetter();

        void clearAccessor();
    }

    static Slot stringSlot(PropertyTable table, String key) {
        return new Slot() {
            @Override
            public boolean exists() {
                return table.has(key) || table.hasAccessor(key);
            }

            @Override
            public boolean hasAccessor() {
                return table.hasAccessor(key);
            }

            @Override
            public PropertyFlags flags() {
                return table.getFlags(key);
            }

            @Override
            public void setFlags(PropertyFlags flags) {
                table.setFlags(key, flags);
            }

            @Override
            public JsValue value() {
                return table.get(key);
            }

            @Override
            public boolean hasValue() {
                return table.has(key);
            }

            @Override
            public void defineValue(JsValue value) {
                table.defineValue(key, value);
            }

            @Override
            public void removeValue() {
                table.getProperties().remove(key);
            }

            @Override
            public JsValue getter() {
                return table.getAccessorGetter(key);
            }

            @Override
            public JsValue setter() {
                return table.getAccessorSetter(key);
            }

            @Override
            public void defineAccessor(JsValue getter, JsValue setter) {
                table.defineAccessor(key, getter, setter);
            }

            @Override
            public void clearGetter() {
                table.clearAccessorGetter(key);
            }

            @Override
            public void clearSetter() {
                table.clearAccessorSetter(key);
            }

            @Override
            public void clearAccessor() {
                table.clearAccessor(key);
            }
        };
    }

    static Slot symbolSlot(PropertyTable table, JsSymbol key) {
        return new Slot() {
            @Override
            public boolean exists() {
                return table.hasSymbol(key) || table.hasSymbolAccessor(key);
            }

            @Override
            public boolean hasAccessor() {
                return table.hasSymbolAccessor(key);
            }

            @Override
            public PropertyFlags flags() {
                return table.getSymbolFlags(key);
            }

            @Override
            public void setFlags(PropertyFlags flags) {
                table.setSymbolFlags(key, flags);
            }

            @Override
            public JsValue value() {
                return table.getSymbol(key);
            }

            @Override
            public boolean hasValue() {
                return table.hasSymbol(key);
            }

            @Override
            public void defineValue(JsValue value) {
                table.defineSymbolValue(key, value);
            }

            @Override
            public void removeValue() {
                // A symbol data slot is only ever replaced, never emptied on its own.
            }

            @Override
            public JsValue getter() {
                return table.getSymbolAccessorGetter(key);
            }

            @Override
            public JsValue setter() {
                return table.getSymbolAccessorSetter(key);
            }

            @Override
            public void defineAccessor(JsValue getter, JsValue setter) {
                table.defineSymbolAccessor(key, getter, setter);
            }

            @Override
            public void clearGetter() {
                table.clearSymbolAccessorGetter(key);
            }

            @Override
            public void clearSetter() {
                table.clearSymbolAccessorSetter(key);
            }

            @Override
            public void clearAccessor() {
                table.clearSymbolAccessor(key);
            }
        };
    }

    static void validateAndApply(Slot slot, boolean extensible, String key, PropertyDescriptor descriptor) {
        final var exists = slot.exists();
        if (!exists && !extensible) {
            throw new TypeErrorException("Cannot define property " + key + ", object is not extensible");
        }
        if (exists && !slot.flags().configurable()) {
            checkNonConfigurableRedefine(slot, key, descriptor);
        }
        final var flags = flagsFrom(descriptor, slot, exists);
        if (descriptor.isAccessorDescriptor()) {
            applyAccessorFields(slot, descriptor);
        } else if (descriptor.value() != null || descriptor.writable() != null || !exists || !slot.hasAccessor()) {
            // Converting an accessor property into a data property must drop the stale
            // getter/setter, or a later read would still find the old accessor entry. A generic
            // descriptor over an existing accessor takes neither arm, so the accessor survives.
            slot.clearAccessor();
            slot.defineValue(descriptor.value() != null
                    ? descriptor.value()
                    : exists && slot.hasValue() ? slot.value() : JsUndefined.getInstance());
        }
        slot.setFlags(flags);
    }

    private static void applyAccessorFields(Slot slot, PropertyDescriptor descriptor) {
        // A field absent from the new descriptor keeps the property's current getter/setter
        // (only meaningful if it was already an accessor) rather than defaulting to none, so a
        // {get: fn2} redefine doesn't silently drop an untouched existing setter.
        final var wasAccessor = slot.hasAccessor();
        final var existingGetter = wasAccessor ? slot.getter() : null;
        final var existingSetter = wasAccessor ? slot.setter() : null;
        final var getter = descriptor.getter() == null ? existingGetter : callableOrNull(descriptor.getter());
        final var setter = descriptor.setter() == null ? existingSetter : callableOrNull(descriptor.setter());
        slot.removeValue();
        // defineAccessor only ever adds a non-null side, so a field the new descriptor names
        // but resolves to null (e.g. an explicit `get: undefined`) must be cleared separately.
        if (descriptor.getter() != null && getter == null) {
            slot.clearGetter();
        }
        if (descriptor.setter() != null && setter == null) {
            slot.clearSetter();
        }
        // Reaching this method already means descriptor.isAccessorDescriptor() (the guard in
        // validateAndApply), so it always installs a genuine accessor - even when both resolved
        // sides are null, e.g. {get: undefined, set: undefined}: per spec that is still an
        // accessor property (reads as undefined, rejects writes), not a data property.
        slot.defineAccessor(getter, setter);
    }

    private static JsValue callableOrNull(JsValue value) {
        return isCallable(value) ? value : null;
    }

    private static PropertyFlags flagsFrom(PropertyDescriptor descriptor, Slot slot, boolean exists) {
        final var current = exists ? slot.flags() : new PropertyFlags(false, false, false);
        return new PropertyFlags(descriptor.writableOr(current.writable()),
                descriptor.enumerableOr(current.enumerable()), descriptor.configurableOr(current.configurable()));
    }

    private static void checkNonConfigurableRedefine(Slot slot, String key, PropertyDescriptor descriptor) {
        if (Boolean.TRUE.equals(descriptor.configurable())) {
            throw redefineError(key);
        }
        if (descriptor.enumerable() != null && descriptor.enumerable() != slot.flags().enumerable()) {
            throw redefineError(key);
        }
        final var currentIsAccessor = slot.hasAccessor();
        final var descriptorIsData = descriptor.value() != null || descriptor.writable() != null;
        if ((descriptor.isAccessorDescriptor() && !currentIsAccessor) || (descriptorIsData && currentIsAccessor)) {
            throw redefineError(key);
        }
        if (currentIsAccessor) {
            checkAccessorIdentity(slot, key, descriptor);
            return;
        }
        if (slot.hasValue() && !slot.flags().writable()) {
            if (Boolean.TRUE.equals(descriptor.writable())) {
                throw redefineError(key);
            }
            if (descriptor.value() != null && isNotSameValue(slot.value(), descriptor.value())) {
                throw redefineError(key);
            }
        }
    }

    private static void checkAccessorIdentity(Slot slot, String key, PropertyDescriptor descriptor) {
        if (descriptor.getter() != null && isNotSameValue(descriptor.getter(), orUndefined(slot.getter()))) {
            throw redefineError(key);
        }
        if (descriptor.setter() != null && isNotSameValue(descriptor.setter(), orUndefined(slot.setter()))) {
            throw redefineError(key);
        }
    }

    static PropertyDescriptor describe(Slot slot) {
        final var flags = slot.flags();
        return slot.hasAccessor()
                ? PropertyDescriptor.accessor(slot.getter(), slot.setter(), flags)
                : PropertyDescriptor.data(slot.value(), flags);
    }

    // name/length/prototype are synthesised at lookup time rather than stored, so a redefine has to
    // see the real existing property (and its real flags) instead of treating the key as absent.
    static void materialiseMetadata(JsValue target, PropertyTable table, String key) {
        if (!(target instanceof JsCallableProperties callable) || table.has(key) || !metadataKey(callable, key)) {
            return;
        }
        if ("prototype".equals(key)) {
            table.defineValue(key, prototypeValue(callable));
            table.setFlags(key, new PropertyFlags(isPrototypeWritable(callable), false, false));
            return;
        }
        table.defineValue(key, FunctionProtoBuiltins.metadata(target, key));
        table.setFlags(key, new PropertyFlags(false, false, true));
    }

    static PropertyDescriptor metadataDescriptor(JsValue target, JsCallableProperties callable, String key) {
        if ("prototype".equals(key)) {
            return PropertyDescriptor.data(prototypeValue(callable),
                    new PropertyFlags(isPrototypeWritable(callable), false, false));
        }
        return PropertyDescriptor.data(FunctionProtoBuiltins.metadata(target, key),
                new PropertyFlags(false, false, true));
    }

    // A builtin constructor's `prototype` is non-writable; an ordinary function's is writable.
    private static boolean isPrototypeWritable(JsCallableProperties callable) {
        return !(callable instanceof JsNativeFunction nativeFunction) || !nativeFunction.isConstructor();
    }

    public static boolean metadataKey(JsCallableProperties callable, String key) {
        if (callable.isMetadataDeleted(key)) {
            return false;
        }
        return "name".equals(key) || "length".equals(key)
                || ("prototype".equals(key) && hasPrototypeProperty(callable));
    }

    static List<String> metadataKeys(JsCallableProperties callable) {
        final var candidates = hasPrototypeProperty(callable)
                ? List.of("length", "name", "prototype")
                : List.of("length", "name");
        return candidates.stream().filter(key -> metadataKey(callable, key)).toList();
    }

    // Deliberately not InterpreterUtils.isConstructor: a generator function is not a constructor
    // but does own a `prototype` property (the object its instances are linked to).
    private static boolean hasPrototypeProperty(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return function.isConstructor() || function.isGenerator();
        }
        return callable instanceof JsNativeFunction nativeFunction && nativeFunction.getPrototype() != null;
    }

    private static JsValue prototypeValue(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return function.getPrototype();
        }
        return callable instanceof JsNativeFunction nativeFunction && nativeFunction.getPrototype() != null
                ? nativeFunction.getPrototype()
                : JsUndefined.getInstance();
    }

    public static String keyName(JsValue key) {
        return key instanceof JsString string ? string.getValue() : JsCoercion.toStr(key);
    }

    public static boolean isNotSameValue(JsValue a, JsValue b) {
        if (a instanceof JsNumber na && b instanceof JsNumber nb) {
            // Double.compare implements SameValue for numbers: it distinguishes +0/-0 and treats
            // NaN as equal to NaN.
            return Double.compare(na.getValue(), nb.getValue()) != 0;
        }
        return !JsOperators.strictEquals(a, b);
    }

    static TypeErrorException redefineError(String key) {
        return new TypeErrorException("Cannot redefine property: " + key);
    }

    static JsValue orUndefined(JsValue value) {
        return value == null ? JsUndefined.getInstance() : value;
    }

    static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }
}
