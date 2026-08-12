package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.ownValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.JsOperators;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsObject.PropertyFlags;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ObjectBuiltins {
    private ObjectBuiltins() {
    }

    public static JsNativeFunction create(IterableToList iterableToList, InterpreterOps ops, Invoker invoker) {
        final var object = new JsNativeFunction("Object", (_, args) -> coerceToObject(args));
        object.setProperty("keys", new JsNativeFunction("keys", (_, args) -> keys(args, ops)));
        object.setProperty("values", new JsNativeFunction("values", (_, args) -> values(args, ops)));
        object.setProperty("entries", new JsNativeFunction("entries", (_, args) -> entries(args, ops)));
        object.setProperty("assign", new JsNativeFunction("assign", (_, args) -> assign(args, ops)));
        object.setProperty("freeze", new JsNativeFunction("freeze", (_, args) -> freeze(args)));
        object.setProperty("isFrozen", new JsNativeFunction("isFrozen", (_, args) -> isFrozen(args)));
        object.setProperty("seal", new JsNativeFunction("seal", (_, args) -> seal(args)));
        object.setProperty("isSealed", new JsNativeFunction("isSealed", (_, args) -> isSealed(args)));
        object.setProperty("preventExtensions", new JsNativeFunction("preventExtensions", (_, args) -> {
            ops.preventExtensions(first(args));
            return first(args);
        }));
        object.setProperty("isExtensible",
                new JsNativeFunction("isExtensible", (_, args) -> JsBoolean.of(ops.isExtensible(first(args)))));
        object.setProperty("create", new JsNativeFunction("create", (_, args) -> createObject(args, ops)));
        object.setProperty("getPrototypeOf",
                new JsNativeFunction("getPrototypeOf", (_, args) -> ops.getPrototypeOf(first(args))));
        object.setProperty("setPrototypeOf", new JsNativeFunction("setPrototypeOf", (_, args) -> {
            ops.setPrototypeOf(first(args), argAt(args, 1));
            return first(args);
        }));
        object.setProperty("defineProperty", new JsNativeFunction("defineProperty", (_, args) -> {
            ops.defineProperty(first(args), argAt(args, 1), argAt(args, 2));
            return first(args);
        }));
        object.setProperty("defineProperties",
                new JsNativeFunction("defineProperties", (_, args) -> defineProperties(args, ops)));
        object.setProperty("getOwnPropertyNames",
                new JsNativeFunction("getOwnPropertyNames", (_, args) -> getOwnPropertyNames(args, ops)));
        object.setProperty("getOwnPropertyDescriptor", new JsNativeFunction("getOwnPropertyDescriptor",
                (_, args) -> ops.getOwnPropertyDescriptor(first(args), argAt(args, 1))));
        object.setProperty("getOwnPropertyDescriptors",
                new JsNativeFunction("getOwnPropertyDescriptors", (_, args) -> getOwnPropertyDescriptors(args, ops)));
        object.setProperty("fromEntries",
                new JsNativeFunction("fromEntries", (_, args) -> fromEntries(args, iterableToList)));
        object.setProperty("hasOwn", new JsNativeFunction("hasOwn", (_, args) -> hasOwn(args)));
        object.setProperty("groupBy",
                new JsNativeFunction("groupBy", (_, args) -> groupBy(args, iterableToList, invoker)));
        object.setProperty("is", new JsNativeFunction("is", (_, args) -> is(args)));
        object.setProperty("getOwnPropertySymbols",
                new JsNativeFunction("getOwnPropertySymbols", (_, args) -> getOwnPropertySymbols(args)));
        return object;
    }

    private static JsValue coerceToObject(List<JsValue> args) {
        final var value = first(args);
        return value instanceof JsObject || value instanceof JsArray || value instanceof JsFunction
                || value instanceof JsNativeFunction ? value : new JsObject();
    }

    private static JsValue is(List<JsValue> args) {
        return JsBoolean.of(!isNotSameValue(argAt(args, 0), argAt(args, 1)));
    }

    private static JsValue getOwnPropertySymbols(List<JsValue> args) {
        final var result = new JsArray();
        if (first(args) instanceof JsObject object) {
            for (final var symbol : object.symbolKeys()) {
                result.push(symbol);
            }
        }
        return result;
    }

    private static JsValue hasOwn(List<JsValue> args) {
        if (args.size() < 2) {
            return JsBoolean.of(false);
        }
        if (args.get(1) instanceof JsSymbol symbol) {
            return JsBoolean.of(hasOwnSymbol(args.getFirst(), symbol));
        }
        return JsBoolean.of(hasOwnKey(args.getFirst(), JsCoercion.toStr(args.get(1))));
    }

    static boolean hasOwnKey(JsValue target, String key) {
        return switch (target) {
            case JsObject object -> object.has(key) || object.hasAccessor(key);
            case JsClass cls -> cls.getStaticOwner().has(key) || cls.getStaticOwner().hasAccessor(key);
            case JsArray array -> "length".equals(key) || arrayHasIndex(array, key);
            case JsString string -> "length".equals(key) || stringHasIndex(string, key);
            case JsTypedArray typed -> "length".equals(key) || typedHasIndex(typed, key);
            case JsGlobalObject global -> global.getEnv().isDeclared(key);
            case JsCallableProperties callable -> callable.hasProperty(key) || callableMetadataKey(callable, key);
            default -> false;
        };
    }

    static boolean hasOwnSymbol(JsValue target, JsSymbol key) {
        if (target instanceof JsClass cls) {
            return cls.getStaticOwner().hasSymbol(key);
        }
        return target instanceof JsObject object && object.hasSymbol(key);
    }

    // name/length/prototype are synthesised at lookup time rather than stored, so the reflective
    // surface has to report them explicitly.
    private static boolean callableMetadataKey(JsCallableProperties callable, String key) {
        if (callable.isMetadataDeleted(key)) {
            return false;
        }
        return "name".equals(key) || "length".equals(key) || ("prototype".equals(key) && hasPrototype(callable));
    }

    private static boolean hasPrototype(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return !function.isArrow() && !function.isMethod();
        }
        return callable instanceof JsNativeFunction nativeFunction && nativeFunction.getPrototype() != null;
    }

    private static boolean stringHasIndex(JsString string, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index != null && index < string.getValue().length();
    }

    private static boolean typedHasIndex(JsTypedArray typed, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index != null && index < typed.length();
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
                for (final var key : enumerableProxyStringKeys(proxy, ops)) {
                    result.push(new JsString(key));
                }
            }
            case JsObject object -> {
                for (final var key : object.keys()) {
                    if (object.isEnumerable(key)) {
                        result.push(new JsString(key));
                    }
                }
            }
            case JsClass cls -> {
                final var owner = cls.getStaticOwner();
                for (final var key : owner.keys()) {
                    if (owner.isEnumerable(key)) {
                        result.push(new JsString(key));
                    }
                }
            }
            case JsArray array -> {
                for (var i = 0; i < array.length(); i++) {
                    result.push(new JsString(Integer.toString(i)));
                }
            }
            case JsGlobalObject global -> {
                for (final var name : global.getEnv().enumerableGlobalNames()) {
                    result.push(new JsString(name));
                }
            }
            case JsCallableProperties callable -> {
                for (final var key : callable.enumerablePropertyKeys()) {
                    result.push(new JsString(key));
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
                for (final var key : enumerableProxyStringKeys(proxy, ops)) {
                    result.push(ops.getMember(proxy, new JsString(key)));
                }
            }
            case JsObject object -> {
                for (final var key : object.keys()) {
                    if (object.isEnumerable(key)) {
                        result.push(ownValue(object, key, ops));
                    }
                }
            }
            case JsArray array -> {
                for (final var value : array.getElements()) {
                    result.push(value);
                }
            }
            case JsGlobalObject global -> {
                for (final var name : global.getEnv().enumerableGlobalNames()) {
                    result.push(global.getEnv().tryGet(name));
                }
            }
            case JsCallableProperties callable -> {
                for (final var key : callable.enumerablePropertyKeys()) {
                    result.push(callable.getProperty(key));
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
                for (final var key : enumerableProxyStringKeys(proxy, ops)) {
                    result.push(new JsArray(List.of(new JsString(key), ops.getMember(proxy, new JsString(key)))));
                }
            }
            case JsObject object -> {
                for (final var key : object.keys()) {
                    if (object.isEnumerable(key)) {
                        result.push(new JsArray(List.of(new JsString(key), ownValue(object, key, ops))));
                    }
                }
            }
            case JsArray array -> {
                final var elements = array.getElements();
                for (var i = 0; i < elements.size(); i++) {
                    result.push(new JsArray(List.of(new JsString(Integer.toString(i)), elements.get(i))));
                }
            }
            case JsGlobalObject global -> {
                for (final var name : global.getEnv().enumerableGlobalNames()) {
                    result.push(new JsArray(
                            List.of(new JsString(name), Objects.requireNonNull(global.getEnv().tryGet(name)))));
                }
            }
            case JsCallableProperties callable -> {
                for (final var key : callable.enumerablePropertyKeys()) {
                    result.push(new JsArray(List.of(new JsString(key), callable.getProperty(key))));
                }
            }
            default -> {
            }
        }
        return result;
    }

    private static JsValue assign(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsObject target)) {
            return first(args);
        }
        for (var i = 1; i < args.size(); i++) {
            if (args.get(i) instanceof JsObject source) {
                for (final var key : source.keys()) {
                    if (source.isEnumerable(key)) {
                        target.set(key, ownValue(source, key, ops));
                    }
                }
                for (final var symbol : source.symbolKeys()) {
                    target.setSymbol(symbol, source.getSymbol(symbol));
                }
            } else if (args.get(i) instanceof JsCallableProperties callable) {
                for (final var key : callable.enumerablePropertyKeys()) {
                    target.set(key, callable.getProperty(key));
                }
            }
        }
        return target;
    }

    private static JsValue freeze(List<JsValue> args) {
        final var target = first(args);
        switch (target) {
            case JsObject object -> object.freeze();
            case JsArray array -> array.freeze();
            default -> {
            }
        }
        return target;
    }

    private static JsValue isFrozen(List<JsValue> args) {
        return JsBoolean.of(switch (first(args)) {
            case JsObject object -> object.isFrozen();
            case JsArray array -> array.isFrozen();
            default -> true;
        });
    }

    private static JsValue seal(List<JsValue> args) {
        final var target = first(args);
        switch (target) {
            case JsObject object -> object.seal();
            case JsArray array -> array.seal();
            default -> {
            }
        }
        return target;
    }

    private static JsValue isSealed(List<JsValue> args) {
        return JsBoolean.of(switch (first(args)) {
            case JsObject object -> object.isSealed();
            case JsArray array -> array.isSealed();
            default -> true;
        });
    }

    public static JsValue preventExtensions(List<JsValue> args) {
        final var target = first(args);
        switch (target) {
            case JsObject object -> object.preventExtensions();
            case JsArray array -> array.preventExtensions();
            default -> {
            }
        }
        return target;
    }

    public static JsValue isExtensible(List<JsValue> args) {
        return JsBoolean.of(switch (first(args)) {
            case JsObject object -> object.isExtensible();
            case JsArray array -> array.isExtensible();
            default -> false;
        });
    }

    private static JsValue createObject(List<JsValue> args, InterpreterOps ops) {
        final var object = new JsObject();
        final var proto = first(args);
        if (proto instanceof JsObject protoObject) {
            object.setProto(protoObject);
        }
        if (args.size() > 1 && args.get(1) instanceof JsObject props) {
            applyProperties(object, props, ops);
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
        if (args.size() > 2 && args.get(2) instanceof JsObject descriptor) {
            if (target instanceof JsObject object) {
                applyDescriptor(object, JsCoercion.toStr(args.get(1)), descriptor);
            } else if (target instanceof JsClass cls) {
                applyDescriptor(cls.getStaticOwner(), JsCoercion.toStr(args.get(1)), descriptor);
            }
        }
        return target;
    }

    private static JsValue defineProperties(List<JsValue> args, InterpreterOps ops) {
        final var target = first(args);
        if (target instanceof JsObject object && args.size() > 1 && args.get(1) instanceof JsObject props) {
            applyProperties(object, props, ops);
        }
        return target;
    }

    private static void applyProperties(JsObject object, JsObject props, InterpreterOps ops) {
        for (final var key : props.keys()) {
            if (ownValue(props, key, ops) instanceof JsObject descriptor) {
                applyDescriptor(object, key, descriptor);
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
        final var currentIsAccessor = object.hasAccessor(key);
        final var descriptorIsAccessor = descriptor.has("get") || descriptor.has("set");
        final var descriptorIsData = descriptor.has("value") || descriptor.has("writable");
        if ((descriptorIsAccessor && !currentIsAccessor) || (descriptorIsData && currentIsAccessor)) {
            throw redefineError(key);
        }
        if (currentIsAccessor) {
            if (descriptor.has("get")
                    && isNotSameValue(descriptor.get("get"), orUndefined(object.getAccessorGetter(key)))) {
                throw redefineError(key);
            }
            if (descriptor.has("set")
                    && isNotSameValue(descriptor.get("set"), orUndefined(object.getAccessorSetter(key)))) {
                throw redefineError(key);
            }
            return;
        }
        if (object.has(key) && !object.isWritable(key)) {
            if (descriptor.has("writable") && JsCoercion.toBoolean(descriptor.get("writable"))) {
                throw redefineError(key);
            }
            if (descriptor.has("value") && isNotSameValue(object.get(key), descriptor.get("value"))) {
                throw redefineError(key);
            }
        }
    }

    private static JsValue orUndefined(JsValue value) {
        return value == null ? JsUndefined.getInstance() : value;
    }

    private static boolean isNotSameValue(JsValue a, JsValue b) {
        if (a instanceof JsNumber na && b instanceof JsNumber nb) {
            // Double.compare implements SameValue for numbers: it distinguishes +0/-0 and treats
            // NaN as equal to NaN.
            return Double.compare(na.getValue(), nb.getValue()) != 0;
        }
        return !JsOperators.strictEquals(a, b);
    }

    private static TypeErrorException redefineError(String key) {
        return new TypeErrorException("Cannot redefine property: " + key);
    }

    // Re-filters a proxy's ownKeys trap result down to enumerable string keys, so Object.keys/values/
    // entries (and for-in) honour enumerability for proxies as they do for plain objects.
    private static List<String> enumerableProxyStringKeys(JsValue proxy, InterpreterOps ops) {
        final var keys = new ArrayList<String>();
        for (final var key : ops.ownKeys(proxy)) {
            if (!(key instanceof JsString string)) {
                continue;
            }
            final var descriptor = ops.getOwnPropertyDescriptor(proxy, key);
            if (descriptor instanceof JsObject desc && desc.has("enumerable")
                    && JsCoercion.toBoolean(desc.get("enumerable"))) {
                keys.add(string.getValue());
            }
        }
        return keys;
    }

    private static boolean isCallable(JsValue value) {
        return value instanceof JsFunction || value instanceof JsNativeFunction;
    }

    private static JsValue getOwnPropertyDescriptors(List<JsValue> args, InterpreterOps ops) {
        if (!(first(args) instanceof JsObject target)) {
            throw new TypeErrorException("Object.getOwnPropertyDescriptors called on non-object");
        }
        final var result = new JsObject();
        for (final var key : target.keys()) {
            result.set(key, ops.getOwnPropertyDescriptor(target, new JsString(key)));
        }
        for (final var symbol : target.symbolKeys()) {
            result.setSymbol(symbol, ops.getOwnPropertyDescriptor(target, symbol));
        }
        return result;
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
            case JsClass cls -> {
                for (final var key : cls.getStaticOwner().keys()) {
                    result.push(new JsString(key));
                }
            }
            case JsArray array -> {
                for (var i = 0; i < array.length(); i++) {
                    result.push(new JsString(Integer.toString(i)));
                }
                result.push(new JsString("length"));
            }
            case JsGlobalObject global -> {
                for (final var name : global.getEnv().allGlobalNames()) {
                    result.push(new JsString(name));
                }
            }
            case JsCallableProperties callable -> {
                for (final var key : callableMetadataKeys(callable)) {
                    result.push(new JsString(key));
                }
                for (final var key : callable.propertyKeys()) {
                    if (!callableMetadataKey(callable, key)) {
                        result.push(new JsString(key));
                    }
                }
            }
            default -> {
            }
        }
        return result;
    }

    private static List<String> callableMetadataKeys(JsCallableProperties callable) {
        final var candidates = hasPrototype(callable)
                ? List.of("length", "name", "prototype")
                : List.of("length", "name");
        return candidates.stream().filter(key -> callableMetadataKey(callable, key)).toList();
    }

    public static JsValue getOwnPropertyDescriptor(List<JsValue> args) {
        if (args.size() > 1 && first(args) instanceof JsCallableProperties callable) {
            return callableDescriptor(first(args), callable, args.get(1));
        }
        if (args.size() > 1 && first(args) instanceof JsGlobalObject global) {
            return globalDescriptor(global, args.get(1));
        }
        final var target = first(args) instanceof JsClass cls ? cls.getStaticOwner() : first(args);
        if (!(target instanceof JsObject object) || args.size() < 2) {
            return JsUndefined.getInstance();
        }
        if (args.get(1) instanceof JsSymbol symbol) {
            return symbolDescriptor(object, symbol);
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

    // Data properties only: accessors and descriptor flags on a callable are out of scope, so a
    // script-assigned property reports the JsObject default and a builtin static reports
    // non-enumerable.
    private static JsValue callableDescriptor(JsValue target, JsCallableProperties callable, JsValue keyValue) {
        if (keyValue instanceof JsSymbol) {
            return JsUndefined.getInstance();
        }
        final var key = JsCoercion.toStr(keyValue);
        if (!callable.hasProperty(key)) {
            return callableMetadataKey(callable, key)
                    ? metadataDescriptor(target, callable, key)
                    : JsUndefined.getInstance();
        }
        final var descriptor = new JsObject();
        descriptor.set("value", callable.getProperty(key));
        descriptor.set("writable", JsBoolean.of(true));
        descriptor.set("enumerable", JsBoolean.of(callable.enumerablePropertyKeys().contains(key)));
        descriptor.set("configurable", JsBoolean.of(true));
        return descriptor;
    }

    private static JsValue metadataDescriptor(JsValue target, JsCallableProperties callable, String key) {
        final var descriptor = new JsObject();
        if ("prototype".equals(key)) {
            descriptor.set("value", prototypeOf(callable));
            descriptor.set("writable", JsBoolean.of(true));
            descriptor.set("enumerable", JsBoolean.of(false));
            descriptor.set("configurable", JsBoolean.of(false));
            return descriptor;
        }
        descriptor.set("value", FunctionProtoBuiltins.metadata(target, key));
        descriptor.set("writable", JsBoolean.of(false));
        descriptor.set("enumerable", JsBoolean.of(false));
        descriptor.set("configurable", JsBoolean.of(true));
        return descriptor;
    }

    private static JsValue prototypeOf(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return function.getPrototype();
        }
        return callable instanceof JsNativeFunction nativeFunction && nativeFunction.getPrototype() != null
                ? nativeFunction.getPrototype()
                : JsUndefined.getInstance();
    }

    private static JsValue globalDescriptor(JsGlobalObject global, JsValue keyValue) {
        if (keyValue instanceof JsSymbol) {
            return JsUndefined.getInstance();
        }
        final var key = JsCoercion.toStr(keyValue);
        final var env = global.getEnv();
        if (!env.isDeclared(key)) {
            return JsUndefined.getInstance();
        }
        final var descriptor = new JsObject();
        descriptor.set("value", InterpreterUtils.orUndefined(env.tryGet(key)));
        descriptor.set("writable", JsBoolean.of(true));
        descriptor.set("enumerable", JsBoolean.of(env.enumerableGlobalNames().contains(key)));
        descriptor.set("configurable", JsBoolean.of(true));
        return descriptor;
    }

    private static JsValue symbolDescriptor(JsObject object, JsSymbol symbol) {
        if (!object.hasSymbol(symbol)) {
            return JsUndefined.getInstance();
        }
        final var descriptor = new JsObject();
        descriptor.set("value", object.getSymbol(symbol));
        descriptor.set("writable", JsBoolean.of(true));
        descriptor.set("enumerable", JsBoolean.of(true));
        descriptor.set("configurable", JsBoolean.of(true));
        return descriptor;
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
