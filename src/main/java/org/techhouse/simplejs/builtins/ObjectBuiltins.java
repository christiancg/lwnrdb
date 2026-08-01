package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ObjectBuiltins {
    private ObjectBuiltins() {
    }

    public static JsObject create(IterableToList iterableToList, InterpreterOps ops, Invoker invoker) {
        final var object = new JsObject();
        object.set("keys", new JsNativeFunction("keys", (_, args) -> keys(args, ops)));
        object.set("values", new JsNativeFunction("values", (_, args) -> values(args, ops)));
        object.set("entries", new JsNativeFunction("entries", (_, args) -> entries(args, ops)));
        object.set("assign", new JsNativeFunction("assign", (_, args) -> assign(args)));
        object.set("freeze", new JsNativeFunction("freeze", (_, args) -> freeze(args)));
        object.set("isFrozen", new JsNativeFunction("isFrozen", (_, args) -> isFrozen(args)));
        object.set("seal", new JsNativeFunction("seal", (_, args) -> seal(args)));
        object.set("isSealed", new JsNativeFunction("isSealed", (_, args) -> isSealed(args)));
        object.set("preventExtensions", new JsNativeFunction("preventExtensions", (_, args) -> {
            ops.preventExtensions(first(args));
            return first(args);
        }));
        object.set("isExtensible",
                new JsNativeFunction("isExtensible", (_, args) -> JsBoolean.of(ops.isExtensible(first(args)))));
        object.set("create", new JsNativeFunction("create", (_, args) -> createObject(args)));
        object.set("getPrototypeOf",
                new JsNativeFunction("getPrototypeOf", (_, args) -> ops.getPrototypeOf(first(args))));
        object.set("setPrototypeOf", new JsNativeFunction("setPrototypeOf", (_, args) -> {
            ops.setPrototypeOf(first(args), argAt(args, 1));
            return first(args);
        }));
        object.set("defineProperty", new JsNativeFunction("defineProperty", (_, args) -> {
            ops.defineProperty(first(args), argAt(args, 1), argAt(args, 2));
            return first(args);
        }));
        object.set("defineProperties", new JsNativeFunction("defineProperties", (_, args) -> defineProperties(args)));
        object.set("getOwnPropertyNames",
                new JsNativeFunction("getOwnPropertyNames", (_, args) -> getOwnPropertyNames(args, ops)));
        object.set("getOwnPropertyDescriptor", new JsNativeFunction("getOwnPropertyDescriptor",
                (_, args) -> ops.getOwnPropertyDescriptor(first(args), argAt(args, 1))));
        object.set("fromEntries", new JsNativeFunction("fromEntries", (_, args) -> fromEntries(args, iterableToList)));
        object.set("hasOwn", new JsNativeFunction("hasOwn", (_, args) -> hasOwn(args)));
        object.set("groupBy", new JsNativeFunction("groupBy", (_, args) -> groupBy(args, iterableToList, invoker)));
        return object;
    }

    private static JsValue hasOwn(List<JsValue> args) {
        if (args.size() < 2) {
            return JsBoolean.of(false);
        }
        final var key = JsCoercion.toStr(args.get(1));
        return switch (args.getFirst()) {
            case JsObject object -> JsBoolean.of(object.has(key) || object.hasAccessor(key));
            case JsArray array -> JsBoolean.of("length".equals(key) || arrayHasIndex(array, key));
            default -> JsBoolean.of(false);
        };
    }

    private static boolean arrayHasIndex(JsArray array, String key) {
        try {
            final var index = Integer.parseInt(key);
            return index >= 0 && index < array.length();
        } catch (NumberFormatException ignored) {
            return array.getProperty(key) != null;
        }
    }

    private static JsValue groupBy(List<JsValue> args, IterableToList iterableToList, Invoker invoker) {
        final var source = first(args);
        final var callback = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        final var result = new JsObject();
        final var items = source instanceof JsArray array ? array.getElements() : iterableToList.drain(source);
        for (var i = 0; i < items.size(); i++) {
            final var key = JsCoercion
                    .toStr(invoker.call(callback, JsUndefined.getInstance(), List.of(items.get(i), new JsNumber(i))));
            final JsArray bucket;
            if (result.get(key) instanceof JsArray existing) {
                bucket = existing;
            } else {
                bucket = new JsArray();
                result.defineValue(key, bucket);
            }
            bucket.push(items.get(i));
        }
        return result;
    }

    private static JsValue keys(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        switch (first(args)) {
            case JsProxy proxy -> {
                for (final var key : ops.ownKeys(proxy)) {
                    result.push(key);
                }
            }
            case JsObject object -> {
                for (final var key : object.keys()) {
                    if (object.isEnumerable(key)) {
                        result.push(new JsString(key));
                    }
                }
            }
            case JsArray array -> {
                for (var i = 0; i < array.length(); i++) {
                    result.push(new JsString(Integer.toString(i)));
                }
            }
            default -> {
            }
        }
        return result;
    }

    private static JsValue values(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        switch (first(args)) {
            case JsProxy proxy -> {
                for (final var key : ops.ownKeys(proxy)) {
                    result.push(ops.getMember(proxy, key));
                }
            }
            case JsObject object -> {
                for (final var entry : object.getProperties().entrySet()) {
                    if (object.isEnumerable(entry.getKey())) {
                        result.push(entry.getValue());
                    }
                }
            }
            case JsArray array -> {
                for (final var value : array.getElements()) {
                    result.push(value);
                }
            }
            default -> {
            }
        }
        return result;
    }

    private static JsValue entries(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        switch (first(args)) {
            case JsProxy proxy -> {
                for (final var key : ops.ownKeys(proxy)) {
                    result.push(new JsArray(List.of(key, ops.getMember(proxy, key))));
                }
            }
            case JsObject object -> {
                for (final var entry : object.getProperties().entrySet()) {
                    if (object.isEnumerable(entry.getKey())) {
                        result.push(new JsArray(List.of(new JsString(entry.getKey()), entry.getValue())));
                    }
                }
            }
            case JsArray array -> {
                final var elements = array.getElements();
                for (var i = 0; i < elements.size(); i++) {
                    result.push(new JsArray(List.of(new JsString(Integer.toString(i)), elements.get(i))));
                }
            }
            default -> {
            }
        }
        return result;
    }

    private static JsValue assign(List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsObject target)) {
            return first(args);
        }
        for (var i = 1; i < args.size(); i++) {
            if (args.get(i) instanceof JsObject source) {
                for (final var entry : source.getProperties().entrySet()) {
                    if (source.isEnumerable(entry.getKey())) {
                        target.set(entry.getKey(), entry.getValue());
                    }
                }
            }
        }
        return target;
    }

    private static JsValue freeze(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object) {
            object.freeze();
        }
        return target;
    }

    private static JsValue isFrozen(List<JsValue> args) {
        return JsBoolean.of(!(first(args) instanceof JsObject object) || object.isFrozen());
    }

    private static JsValue seal(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object) {
            object.seal();
        }
        return target;
    }

    private static JsValue isSealed(List<JsValue> args) {
        return JsBoolean.of(!(first(args) instanceof JsObject object) || object.isSealed());
    }

    public static JsValue preventExtensions(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object) {
            object.preventExtensions();
        }
        return target;
    }

    public static JsValue isExtensible(List<JsValue> args) {
        return JsBoolean.of(first(args) instanceof JsObject object && object.isExtensible());
    }

    private static JsValue createObject(List<JsValue> args) {
        final var object = new JsObject();
        final var proto = first(args);
        if (proto instanceof JsObject protoObject) {
            object.setProto(protoObject);
        }
        if (args.size() > 1 && args.get(1) instanceof JsObject props) {
            applyProperties(object, props);
        }
        return object;
    }

    public static JsValue getPrototypeOf(List<JsValue> args) {
        if (first(args) instanceof JsObject object && object.getProto() != null) {
            return object.getProto();
        }
        return JsNull.getInstance();
    }

    public static JsValue setPrototypeOf(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object) {
            object.setProto(args.size() > 1 && args.get(1) instanceof JsObject proto ? proto : null);
        }
        return target;
    }

    public static JsValue defineProperty(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object && args.size() > 2 && args.get(2) instanceof JsObject descriptor) {
            applyDescriptor(object, JsCoercion.toStr(args.get(1)), descriptor);
        }
        return target;
    }

    private static JsValue defineProperties(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object && args.size() > 1 && args.get(1) instanceof JsObject props) {
            applyProperties(object, props);
        }
        return target;
    }

    private static void applyProperties(JsObject object, JsObject props) {
        for (final var entry : props.getProperties().entrySet()) {
            if (entry.getValue() instanceof JsObject descriptor) {
                applyDescriptor(object, entry.getKey(), descriptor);
            }
        }
    }

    private static void applyDescriptor(JsObject object, String key, JsObject descriptor) {
        final var exists = object.has(key) || object.hasAccessor(key);
        if (!exists && !object.isExtensible()) {
            throw new TypeErrorException("Cannot define property " + key + ", object is not extensible");
        }
        if (exists && object.isNotConfigurable(key)) {
            checkNonConfigurableRedefine(object, key, descriptor);
        }
        final var flags = flagsFrom(descriptor, object, key, exists);
        final var getter = descriptor.has("get") ? descriptor.get("get") : null;
        final var setter = descriptor.has("set") ? descriptor.get("set") : null;
        if (getter != null || setter != null) {
            object.getProperties().remove(key);
            object.defineAccessor(key, isCallable(getter) ? getter : null, isCallable(setter) ? setter : null);
        } else {
            object.defineValue(key,
                    descriptor.has("value")
                            ? descriptor.get("value")
                            : exists && object.has(key) ? object.get(key) : JsUndefined.getInstance());
        }
        object.setFlags(key, flags);
    }

    private static PropertyFlags flagsFrom(JsObject descriptor, JsObject object, String key, boolean exists) {
        final var current = exists ? object.getFlags(key) : new PropertyFlags(false, false, false);
        final var writable = descriptor.has("writable")
                ? JsCoercion.toBoolean(descriptor.get("writable"))
                : current.writable();
        final var enumerable = descriptor.has("enumerable")
                ? JsCoercion.toBoolean(descriptor.get("enumerable"))
                : current.enumerable();
        final var configurable = descriptor.has("configurable")
                ? JsCoercion.toBoolean(descriptor.get("configurable"))
                : current.configurable();
        return new PropertyFlags(writable, enumerable, configurable);
    }

    private static void checkNonConfigurableRedefine(JsObject object, String key, JsObject descriptor) {
        if (descriptor.has("configurable") && JsCoercion.toBoolean(descriptor.get("configurable"))) {
            throw redefineError(key);
        }
        if (descriptor.has("enumerable")
                && JsCoercion.toBoolean(descriptor.get("enumerable")) != object.isEnumerable(key)) {
            throw redefineError(key);
        }
        if (object.has(key) && !object.isWritable(key)) {
            if (descriptor.has("writable") && JsCoercion.toBoolean(descriptor.get("writable"))) {
                throw redefineError(key);
            }
            if (descriptor.has("value") && !JsOperators.strictEquals(object.get(key), descriptor.get("value"))) {
                throw redefineError(key);
            }
        }
    }

    private static TypeErrorException redefineError(String key) {
        return new TypeErrorException("Cannot redefine property: " + key);
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static JsValue getOwnPropertyNames(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        switch (first(args)) {
            case JsProxy proxy -> {
                for (final var key : ops.ownKeys(proxy)) {
                    result.push(key);
                }
            }
            case JsObject object -> {
                for (final var key : object.keys()) {
                    result.push(new JsString(key));
                }
            }
            case JsArray array -> {
                for (var i = 0; i < array.length(); i++) {
                    result.push(new JsString(Integer.toString(i)));
                }
                result.push(new JsString("length"));
            }
            default -> {
            }
        }
        return result;
    }

    public static JsValue getOwnPropertyDescriptor(List<JsValue> args) {
        if (!(first(args) instanceof JsObject object) || args.size() < 2) {
            return JsUndefined.getInstance();
        }
        final var key = JsCoercion.toStr(args.get(1));
        final var flags = object.getFlags(key);
        final var getter = object.getAccessorGetter(key);
        final var setter = object.getAccessorSetter(key);
        if (getter != null || setter != null) {
            final var descriptor = new JsObject();
            descriptor.set("get", getter == null ? JsUndefined.getInstance() : getter);
            descriptor.set("set", setter == null ? JsUndefined.getInstance() : setter);
            descriptor.set("enumerable", JsBoolean.of(flags.enumerable()));
            descriptor.set("configurable", JsBoolean.of(flags.configurable()));
            return descriptor;
        }
        if (object.has(key)) {
            final var descriptor = new JsObject();
            descriptor.set("value", object.get(key));
            descriptor.set("writable", JsBoolean.of(flags.writable()));
            descriptor.set("enumerable", JsBoolean.of(flags.enumerable()));
            descriptor.set("configurable", JsBoolean.of(flags.configurable()));
            return descriptor;
        }
        return JsUndefined.getInstance();
    }

    private static JsValue fromEntries(List<JsValue> args, IterableToList iterableToList) {
        final var result = new JsObject();
        final var source = first(args);
        final var entries = source instanceof JsArray array ? array.getElements() : iterableToList.drain(source);
        for (final var element : entries) {
            if (element instanceof JsArray pair && pair.length() > 0) {
                final var value = pair.length() > 1 ? pair.get(1) : JsUndefined.getInstance();
                result.set(JsCoercion.toStr(pair.get(0)), value);
            }
        }
        return result;
    }

    private static JsValue first(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    private static JsValue argAt(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
