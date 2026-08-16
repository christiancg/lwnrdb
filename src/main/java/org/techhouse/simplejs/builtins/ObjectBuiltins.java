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
import org.techhouse.simplejs.values.PropertyTable;

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
        object.setProperty("hasOwn", new JsNativeFunction("hasOwn", (_, args) -> hasOwn(args, ops)));
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
        final var table = first(args).ownProperties();
        if (table != null) {
            for (final var symbol : table.symbolKeys()) {
                result.push(symbol);
            }
        }
        return result;
    }

    private static JsValue hasOwn(List<JsValue> args, InterpreterOps ops) {
        if (args.isEmpty() || InterpreterUtils.isNullish(args.getFirst())) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var key = toPropertyKey(argAt(args, 1), ops);
        if (key instanceof JsSymbol symbol) {
            return JsBoolean.of(hasOwnSymbol(args.getFirst(), symbol));
        }
        return JsBoolean.of(hasOwnKey(args.getFirst(), JsCoercion.toStr(key)));
    }

    private static JsValue toPropertyKey(JsValue value, InterpreterOps ops) {
        return JsCoercion.toPropertyKey(value, ops);
    }

    // ToPropertyDescriptor's own validation, run before any [[DefineOwnProperty]] work so an
    // ill-formed descriptor is rejected without half-applying its fields.
    private static void checkDescriptorShape(JsValue descriptor, InterpreterOps ops) {
        final var hasGetter = descHas(descriptor, "get", ops);
        final var hasSetter = descHas(descriptor, "set", ops);
        if ((hasGetter || hasSetter) && (descHas(descriptor, "value", ops) || descHas(descriptor, "writable", ops))) {
            throw new TypeErrorException("Invalid property descriptor. Cannot both specify accessors "
                    + "and a value or writable attribute");
        }
        requireAccessorField(hasGetter, descriptor, "get", "Getter", ops);
        requireAccessorField(hasSetter, descriptor, "set", "Setter", ops);
    }

    private static void requireAccessorField(boolean present, JsValue descriptor, String field, String label,
            InterpreterOps ops) {
        if (!present) {
            return;
        }
        final var value = descGet(descriptor, field, ops);
        if (!isCallable(value) && !(value instanceof JsUndefined)) {
            throw new TypeErrorException(label + " must be a function");
        }
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

    // Deliberately not InterpreterUtils.isConstructor: a generator function is not a constructor
    // but does own a `prototype` property (the object its instances are linked to).
    private static boolean hasPrototype(JsCallableProperties callable) {
        if (callable instanceof JsFunction function) {
            return function.isConstructor() || function.isGenerator();
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
        final var index = InterpreterUtils.arrayIndex(key);
        if (index != null) {
            return (index < array.length() && !array.isHole(index)) || array.hasIndexAccessor(index);
        }
        return array.hasProperty(key) || array.hasPropAccessor(key);
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
                    if (!array.isHole(i) && array.getIndexFlags(i).enumerable()) {
                        result.push(new JsString(Integer.toString(i)));
                    }
                }
                for (final var key : array.namedPropertyKeys()) {
                    if (array.getPropFlags(key).enumerable()) {
                        result.push(new JsString(key));
                    }
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
                for (var i = 0; i < array.length(); i++) {
                    if (!array.isHole(i) && array.getIndexFlags(i).enumerable()) {
                        result.push(ops.getMember(array, new JsString(Integer.toString(i))));
                    }
                }
                for (final var key : array.namedPropertyKeys()) {
                    if (array.getPropFlags(key).enumerable()) {
                        result.push(ops.getMember(array, new JsString(key)));
                    }
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
                for (var i = 0; i < array.length(); i++) {
                    if (!array.isHole(i) && array.getIndexFlags(i).enumerable()) {
                        final var key = Integer.toString(i);
                        result.push(new JsArray(List.of(new JsString(key), ops.getMember(array, new JsString(key)))));
                    }
                }
                for (final var key : array.namedPropertyKeys()) {
                    if (array.getPropFlags(key).enumerable()) {
                        result.push(new JsArray(List.of(new JsString(key), ops.getMember(array, new JsString(key)))));
                    }
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
        final var targetArg = first(args);
        if (targetArg instanceof JsNull || targetArg instanceof JsUndefined) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        if (!(targetArg instanceof JsObject target)) {
            return targetArg;
        }
        for (var i = 1; i < args.size(); i++) {
            if (args.get(i) instanceof JsObject source) {
                for (final var key : source.keys()) {
                    // Routed through the interpreter's [[Set]] (not the raw property map) so a
                    // target accessor's setter fires and a rejected write (non-writable,
                    // non-extensible) throws per spec's Set(..., throwOnFailure=true).
                    if (source.isEnumerable(key)
                            && !ops.setMember(target, new JsString(key), ownValue(source, key, ops))) {
                        throw new TypeErrorException("Cannot assign to read only property '" + key + "' of object");
                    }
                }
                for (final var symbol : source.symbolKeys()) {
                    if (!ops.setMember(target, symbol, source.getSymbol(symbol))) {
                        throw new TypeErrorException("Cannot assign to read only property of object");
                    }
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
        final var proto = first(args);
        if (!(proto instanceof JsObject) && !(proto instanceof JsNull)) {
            throw new TypeErrorException("Object prototype may only be an Object or null: " + JsCoercion.toStr(proto));
        }
        final var object = new JsObject();
        if (proto instanceof JsObject protoObject) {
            object.setProto(protoObject);
        }
        if (args.size() > 1 && !(args.get(1) instanceof JsUndefined)) {
            if (args.get(1) instanceof JsNull) {
                throw new TypeErrorException("Cannot convert undefined or null to object");
            }
            applyPropertiesFrom(object, args.get(1), ops);
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
            final var proto = args.size() > 1 && args.get(1) instanceof JsObject candidate ? candidate : null;
            // OrdinarySetPrototypeOf step 8: a cycle would make every later chain walk unbounded.
            for (var walk = proto; walk != null; walk = walk.getProto()) {
                if (walk == object) {
                    throw new TypeErrorException("Cyclic __proto__ value");
                }
            }
            object.setProto(proto);
        }
        return target;
    }

    public static JsValue defineProperty(List<JsValue> args, InterpreterOps ops) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            throw new TypeErrorException("Object.defineProperty called on non-object");
        }
        if (args.size() < 3 || !InterpreterUtils.isObjectLike(args.get(2))) {
            throw new TypeErrorException("Property description must be an object");
        }
        final var descriptor = args.get(2);
        final var propertyKey = toPropertyKey(args.get(1), ops);
        checkDescriptorShape(descriptor, ops);
        if (target instanceof JsArray array && !(propertyKey instanceof JsSymbol)) {
            applyArrayDescriptor(array, JsCoercion.toStr(propertyKey), descriptor, ops);
            return target;
        }
        if (target instanceof JsGlobalObject global && !(propertyKey instanceof JsSymbol)) {
            applyGlobalDescriptor(global, JsCoercion.toStr(propertyKey), descriptor, ops);
            return target;
        }
        final var table = target.ownProperties();
        if (table != null) {
            if (propertyKey instanceof JsSymbol symbol) {
                applySymbolDescriptor(target, symbol, descriptor, ops);
            } else {
                final var key = JsCoercion.toStr(propertyKey);
                materialiseCallableMetadata(target, table, key);
                validateAndApply(stringSlot(table, key), target.isExtensible(), key, descriptor, ops);
            }
        }
        return target;
    }

    // name/length/prototype are synthesised at lookup time rather than stored, so a redefine has to
    // see the real existing property (and its real flags) instead of treating the key as absent.
    private static void materialiseCallableMetadata(JsValue target, PropertyTable table, String key) {
        if (!(target instanceof JsCallableProperties callable) || table.has(key)
                || !callableMetadataKey(callable, key)) {
            return;
        }
        if ("prototype".equals(key)) {
            table.defineValue(key, prototypeOf(callable));
            final var writable = !(callable instanceof JsNativeFunction nativeFunction)
                    || !nativeFunction.isConstructor();
            table.setFlags(key, new PropertyFlags(writable, false, false));
            return;
        }
        table.defineValue(key, FunctionProtoBuiltins.metadata(target, key));
        table.setFlags(key, new PropertyFlags(false, false, true));
    }

    // The global object's string keys live in the Environment, not in a table, so a defineProperty
    // against one writes the binding through rather than shadowing it with a table entry.
    private static void applyGlobalDescriptor(JsGlobalObject global, String key, JsValue descriptor,
            InterpreterOps ops) {
        final var env = global.getEnv();
        if (!env.isDeclared(key)) {
            validateAndApply(stringSlot(global.ownProperties(), key), global.isExtensible(), key, descriptor, ops);
            return;
        }
        final var flags = env.globalFlags(key);
        assert flags != null;
        if (!flags.configurable()) {
            throw redefineError(key);
        }
        if (descHas(descriptor, "value", ops)) {
            env.setGlobal(key, descGet(descriptor, "value", ops));
        }
    }

    // Only "length" and canonical index keys are exotic on an array; any other key is an ordinary
    // own property and goes through the shared ValidateAndApplyPropertyDescriptor over its table.
    private static void applyArrayDescriptor(JsArray array, String key, JsValue descriptor, InterpreterOps ops) {
        final var hasGetter = descHas(descriptor, "get", ops);
        final var hasSetter = descHas(descriptor, "set", ops);
        if ((hasGetter || hasSetter) && (descHas(descriptor, "value", ops) || descHas(descriptor, "writable", ops))) {
            throw new TypeErrorException(
                    "Invalid property descriptor. Cannot both specify accessors and a value or writable attribute, "
                            + key);
        }
        if ("length".equals(key)) {
            if (hasGetter || hasSetter) {
                throw redefineError(key);
            }
            applyArrayLengthDescriptor(array, key, descriptor, ops);
            return;
        }
        final var index = InterpreterUtils.arrayIndex(key);
        if (index == null) {
            validateAndApply(stringSlot(array.ownProperties(), key), array.isExtensible(), key, descriptor, ops);
            return;
        }
        if (hasGetter || hasSetter) {
            applyArrayIndexAccessorDescriptor(array, index, descriptor, ops);
            return;
        }
        applyArrayIndexDescriptor(array, index, key, descriptor, ops);
    }

    // ArraySetLength: the value is applied before the writable attribute, so a length redefine that
    // also clears writable still takes effect.
    private static void applyArrayLengthDescriptor(JsArray array, String key, JsValue descriptor, InterpreterOps ops) {
        final var currentFlags = array.getLengthFlags();
        if (!currentFlags.configurable() && descHas(descriptor, "configurable", ops)
                && JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))) {
            throw redefineError(key);
        }
        if (!currentFlags.configurable() && descHas(descriptor, "enumerable", ops)
                && JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops)) != currentFlags.enumerable()) {
            throw redefineError(key);
        }
        final var writable = descHas(descriptor, "writable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "writable", ops))
                : currentFlags.writable();
        if (descHas(descriptor, "value", ops)) {
            final var newLength = requireArrayLength(descGet(descriptor, "value", ops));
            if (!currentFlags.writable() && newLength != array.length()) {
                throw new TypeErrorException("Cannot redefine property: length");
            }
            array.defineLength(newLength);
        } else if (!currentFlags.writable() && writable) {
            throw redefineError(key);
        }
        array.setLengthWritable(writable);
    }

    private static void applyArrayIndexDescriptor(JsArray array, int index, String key, JsValue descriptor,
            InterpreterOps ops) {
        final var hasValue = descHas(descriptor, "value", ops);
        final var value = hasValue ? descGet(descriptor, "value", ops) : null;
        final var exists = (index < array.length() && !array.isHole(index)) || array.hasIndexAccessor(index);
        final var currentFlags = exists ? array.getIndexFlags(index) : new JsObject.PropertyFlags(false, false, false);
        if (!exists && !array.isExtensible()) {
            throw new TypeErrorException("Cannot define property " + key + ", object is not extensible");
        }
        if (exists && !currentFlags.configurable()) {
            if (descHas(descriptor, "configurable", ops)
                    && JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))) {
                throw redefineError(key);
            }
            if (descHas(descriptor, "enumerable", ops)
                    && JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops)) != currentFlags.enumerable()) {
                throw redefineError(key);
            }
            if (!currentFlags.writable()) {
                if (descHas(descriptor, "writable", ops)
                        && JsCoercion.toBoolean(descGet(descriptor, "writable", ops))) {
                    throw redefineError(key);
                }
                if (hasValue && isNotSameValue(value, array.get(index))) {
                    throw redefineError(key);
                }
            }
        }
        final var flags = new JsObject.PropertyFlags(
                descHas(descriptor, "writable", ops)
                        ? JsCoercion.toBoolean(descGet(descriptor, "writable", ops))
                        : currentFlags.writable(),
                descHas(descriptor, "enumerable", ops)
                        ? JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops))
                        : currentFlags.enumerable(),
                descHas(descriptor, "configurable", ops)
                        ? JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))
                        : currentFlags.configurable());
        array.clearIndexAccessor(index);
        array.defineIndexValue(index, hasValue ? value : exists ? array.get(index) : JsUndefined.getInstance());
        array.setIndexFlags(index, flags);
    }

    private static void applyArrayIndexAccessorDescriptor(JsArray array, int index, JsValue descriptor,
            InterpreterOps ops) {
        final var exists = (index < array.length() && !array.isHole(index)) || array.hasIndexAccessor(index);
        final var currentFlags = exists ? array.getIndexFlags(index) : new JsObject.PropertyFlags(false, false, false);
        if (!exists && !array.isExtensible()) {
            throw new TypeErrorException("Cannot define property " + index + ", object is not extensible");
        }
        if (exists && !currentFlags.configurable()) {
            throw redefineError(String.valueOf(index));
        }
        final var hasGetter = descHas(descriptor, "get", ops);
        final var hasSetter = descHas(descriptor, "set", ops);
        final var getterValue = hasGetter ? descGet(descriptor, "get", ops) : null;
        final var setterValue = hasSetter ? descGet(descriptor, "set", ops) : null;
        if (hasGetter && !isCallable(getterValue) && !(getterValue instanceof JsUndefined)) {
            throw new TypeErrorException("Getter must be a function");
        }
        if (hasSetter && !isCallable(setterValue) && !(setterValue instanceof JsUndefined)) {
            throw new TypeErrorException("Setter must be a function");
        }
        final var flags = new JsObject.PropertyFlags(currentFlags.writable(),
                descHas(descriptor, "enumerable", ops)
                        ? JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops))
                        : currentFlags.enumerable(),
                descHas(descriptor, "configurable", ops)
                        ? JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))
                        : currentFlags.configurable());
        array.defineIndexValue(index, JsUndefined.getInstance());
        array.clearIndexAccessor(index);
        array.defineIndexAccessor(index, isCallable(getterValue) ? getterValue : null,
                isCallable(setterValue) ? setterValue : null);
        array.setIndexFlags(index, flags);
    }

    private static int requireArrayLength(JsValue value) {
        final var number = JsCoercion.toNumber(value);
        final var length = (int) number;
        if (length != number || length < 0) {
            throw new org.techhouse.simplejs.exceptions.RangeErrorException("Invalid array length");
        }
        return length;
    }

    private static void applySymbolDescriptor(JsValue target, JsSymbol symbol, JsValue descriptor, InterpreterOps ops) {
        validateAndApply(symbolSlot(target.ownProperties(), symbol), target.isExtensible(), symbol.getDescription(),
                descriptor, ops);
    }

    private static JsValue defineProperties(List<JsValue> args, InterpreterOps ops) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            throw new TypeErrorException("Object.defineProperties called on non-object");
        }
        // ToObject(Properties): only null/undefined throw; another primitive simply has no keys.
        if (args.size() < 2 || InterpreterUtils.isNullish(args.get(1))) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        applyPropertiesFrom(target, args.get(1), ops);
        return target;
    }

    // ToObject(Properties) accepts any object, so the descriptor bag is read through the member
    // seam rather than requiring a literal JsObject.
    // ObjectDefineProperties step 3 only picks up the *enumerable* own keys, so a bag like Math -
    // whose builtin members are all non-enumerable - contributes only what the caller put on it.
    private static void applyPropertiesFrom(JsValue target, JsValue props, InterpreterOps ops) {
        for (final var key : ops.ownKeys(props)) {
            if (!(key instanceof JsString name) || !isEnumerableOwnKey(props, key, ops)) {
                continue;
            }
            defineProperty(List.of(target, name, ops.getMember(props, key)), ops);
        }
    }

    private static boolean isEnumerableOwnKey(JsValue props, JsValue key, InterpreterOps ops) {
        return ops.getOwnPropertyDescriptor(props, key) instanceof JsObject descriptor
                && JsCoercion.toBoolean(descriptor.get("enumerable"));
    }

    // A descriptor argument's fields (get/set/value/writable/enumerable/configurable) are read via
    // HasProperty+Get per ToPropertyDescriptor, honouring inherited accessors on the descriptor
    // object itself - not just its own properties.
    private static boolean descHas(JsValue descriptor, String key, InterpreterOps ops) {
        return ops.has(descriptor, new JsString(key));
    }

    private static JsValue descGet(JsValue descriptor, String key, InterpreterOps ops) {
        return ops.getMember(descriptor, new JsString(key));
    }

    // ValidateAndApplyPropertyDescriptor, shared by string keys, symbol keys and every value type
    // that carries a PropertyTable. A Slot is one key's storage; an array's exotic index/length arms
    // are handled before this is reached.
    private interface Slot {
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

    private static Slot stringSlot(PropertyTable table, String key) {
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

    private static Slot symbolSlot(PropertyTable table, JsSymbol key) {
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

    private static void validateAndApply(Slot slot, boolean extensible, String key, JsValue descriptor,
            InterpreterOps ops) {
        final var exists = slot.exists();
        if (!exists && !extensible) {
            throw new TypeErrorException("Cannot define property " + key + ", object is not extensible");
        }
        if (exists && !slot.flags().configurable()) {
            checkNonConfigurableRedefine(slot, key, descriptor, ops);
        }
        final var flags = flagsFrom(descriptor, slot, exists, ops);
        final var hasGetter = descHas(descriptor, "get", ops);
        final var hasSetter = descHas(descriptor, "set", ops);
        final var hasValue = descHas(descriptor, "value", ops);
        if ((hasGetter || hasSetter) && (hasValue || descHas(descriptor, "writable", ops))) {
            throw new TypeErrorException("Invalid property descriptor. Cannot both specify accessors "
                    + "and a value or writable attribute, " + key);
        }
        if (hasGetter || hasSetter) {
            applyAccessorFields(slot, descriptor, hasGetter, hasSetter, ops);
        } else if (hasValue || descHas(descriptor, "writable", ops) || !exists || !slot.hasAccessor()) {
            // Converting an accessor property into a data property must drop the stale
            // getter/setter, or a later read would still find the old accessor entry. A generic
            // descriptor over an existing accessor takes neither arm, so the accessor survives.
            slot.clearAccessor();
            slot.defineValue(hasValue
                    ? descGet(descriptor, "value", ops)
                    : exists && slot.hasValue() ? slot.value() : JsUndefined.getInstance());
        }
        slot.setFlags(flags);
    }

    private static void applyAccessorFields(Slot slot, JsValue descriptor, boolean hasGetter, boolean hasSetter,
            InterpreterOps ops) {
        // A field absent from the new descriptor keeps the property's current getter/setter
        // (only meaningful if it was already an accessor) rather than defaulting to none, so a
        // {get: fn2} redefine doesn't silently drop an untouched existing setter.
        final var wasAccessor = slot.hasAccessor();
        final var existingGetter = wasAccessor ? slot.getter() : null;
        final var existingSetter = wasAccessor ? slot.setter() : null;
        final var getterValue = hasGetter ? descGet(descriptor, "get", ops) : null;
        final var setterValue = hasSetter ? descGet(descriptor, "set", ops) : null;
        if (hasGetter && !isCallable(getterValue) && !(getterValue instanceof JsUndefined)) {
            throw new TypeErrorException("Getter must be a function");
        }
        if (hasSetter && !isCallable(setterValue) && !(setterValue instanceof JsUndefined)) {
            throw new TypeErrorException("Setter must be a function");
        }
        final var getter = hasGetter ? (isCallable(getterValue) ? getterValue : null) : existingGetter;
        final var setter = hasSetter ? (isCallable(setterValue) ? setterValue : null) : existingSetter;
        slot.removeValue();
        // defineAccessor only ever adds a non-null side, so a field the new descriptor names
        // but resolves to null (e.g. an explicit `get: undefined`) must be cleared separately.
        if (hasGetter && getter == null) {
            slot.clearGetter();
        }
        if (hasSetter && setter == null) {
            slot.clearSetter();
        }
        if (getter != null || setter != null) {
            slot.defineAccessor(getter, setter);
        } else {
            // 'get'/'set' were both present but neither was callable: still a real accessor
            // property per spec (reads as undefined, rejects writes), approximated here as an
            // inert always-undefined value since defineAccessor needs at least one callable
            // function to register the key at all.
            slot.defineValue(JsUndefined.getInstance());
        }
    }

    private static PropertyFlags flagsFrom(JsValue descriptor, Slot slot, boolean exists, InterpreterOps ops) {
        final var current = exists ? slot.flags() : new PropertyFlags(false, false, false);
        final var writable = descHas(descriptor, "writable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "writable", ops))
                : current.writable();
        final var enumerable = descHas(descriptor, "enumerable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops))
                : current.enumerable();
        final var configurable = descHas(descriptor, "configurable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))
                : current.configurable();
        return new PropertyFlags(writable, enumerable, configurable);
    }

    private static void checkNonConfigurableRedefine(Slot slot, String key, JsValue descriptor, InterpreterOps ops) {
        if (descHas(descriptor, "configurable", ops)
                && JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))) {
            throw redefineError(key);
        }
        if (descHas(descriptor, "enumerable", ops)
                && JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops)) != slot.flags().enumerable()) {
            throw redefineError(key);
        }
        final var currentIsAccessor = slot.hasAccessor();
        final var descriptorIsAccessor = descHas(descriptor, "get", ops) || descHas(descriptor, "set", ops);
        final var descriptorIsData = descHas(descriptor, "value", ops) || descHas(descriptor, "writable", ops);
        if ((descriptorIsAccessor && !currentIsAccessor) || (descriptorIsData && currentIsAccessor)) {
            throw redefineError(key);
        }
        if (currentIsAccessor) {
            if (descHas(descriptor, "get", ops)
                    && isNotSameValue(descGet(descriptor, "get", ops), orUndefined(slot.getter()))) {
                throw redefineError(key);
            }
            if (descHas(descriptor, "set", ops)
                    && isNotSameValue(descGet(descriptor, "set", ops), orUndefined(slot.setter()))) {
                throw redefineError(key);
            }
            return;
        }
        if (slot.hasValue() && !slot.flags().writable()) {
            if (descHas(descriptor, "writable", ops) && JsCoercion.toBoolean(descGet(descriptor, "writable", ops))) {
                throw redefineError(key);
            }
            if (descHas(descriptor, "value", ops) && isNotSameValue(slot.value(), descGet(descriptor, "value", ops))) {
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
        final var target = first(args);
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var result = new JsObject();
        for (final var key : ops.ownKeys(target)) {
            final var descriptor = ops.getOwnPropertyDescriptor(target, key);
            if (descriptor instanceof JsUndefined) {
                continue;
            }
            if (key instanceof JsSymbol symbol) {
                result.setSymbol(symbol, descriptor);
            } else {
                result.set(JsCoercion.toStr(key), descriptor);
            }
        }
        final var table = target.ownProperties();
        if (table != null) {
            for (final var symbol : table.symbolKeys()) {
                result.setSymbol(symbol, ops.getOwnPropertyDescriptor(target, symbol));
            }
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
                    if (!array.isHole(i)) {
                        result.push(new JsString(Integer.toString(i)));
                    }
                }
                for (final var key : array.namedPropertyKeys()) {
                    result.push(new JsString(key));
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
                final var table = first(args).ownProperties();
                if (table != null) {
                    for (final var key : table.keys()) {
                        result.push(new JsString(key));
                    }
                }
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
        final var target = first(args);
        // ToObject(O): only null/undefined throw; any other primitive simply has no own property.
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var keyValue = argAt(args, 1);
        if (target instanceof JsGlobalObject global) {
            return globalDescriptor(global, keyValue);
        }
        if (target instanceof JsArray array && !(keyValue instanceof JsSymbol)) {
            return arrayDescriptor(array, keyValue);
        }
        final var table = target.ownProperties();
        if (table == null) {
            return JsUndefined.getInstance();
        }
        final var slot = keyValue instanceof JsSymbol symbol
                ? symbolSlot(table, symbol)
                : stringSlot(table, JsCoercion.toStr(keyValue));
        if (slot.exists()) {
            return describeSlot(slot);
        }
        if (target instanceof JsCallableProperties callable && !(keyValue instanceof JsSymbol)
                && callableMetadataKey(callable, JsCoercion.toStr(keyValue))) {
            return metadataDescriptor(target, callable, JsCoercion.toStr(keyValue));
        }
        return JsUndefined.getInstance();
    }

    private static JsValue describeSlot(Slot slot) {
        final var flags = slot.flags();
        final var descriptor = new JsObject();
        if (slot.hasAccessor()) {
            descriptor.set("get", orUndefined(slot.getter()));
            descriptor.set("set", orUndefined(slot.setter()));
        } else {
            descriptor.set("value", slot.value());
            descriptor.set("writable", JsBoolean.of(flags.writable()));
        }
        descriptor.set("enumerable", JsBoolean.of(flags.enumerable()));
        descriptor.set("configurable", JsBoolean.of(flags.configurable()));
        return descriptor;
    }

    private static JsValue metadataDescriptor(JsValue target, JsCallableProperties callable, String key) {
        final var descriptor = new JsObject();
        if ("prototype".equals(key)) {
            // A builtin constructor's `prototype` is non-writable; an ordinary function's is
            // writable. Neither is enumerable or configurable.
            final var writable = !(callable instanceof JsNativeFunction nativeFunction)
                    || !nativeFunction.isConstructor();
            descriptor.set("value", prototypeOf(callable));
            descriptor.set("writable", JsBoolean.of(writable));
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
        if (keyValue instanceof JsSymbol symbol) {
            final var slot = symbolSlot(global.ownProperties(), symbol);
            return slot.exists() ? describeSlot(slot) : JsUndefined.getInstance();
        }
        final var key = JsCoercion.toStr(keyValue);
        final var env = global.getEnv();
        if (!env.isDeclared(key)) {
            final var slot = stringSlot(global.ownProperties(), key);
            return slot.exists() ? describeSlot(slot) : JsUndefined.getInstance();
        }
        final var flags = env.globalFlags(key);
        final var descriptor = new JsObject();
        descriptor.set("value", InterpreterUtils.orUndefined(env.tryGet(key)));
        assert flags != null;
        descriptor.set("writable", JsBoolean.of(flags.writable()));
        descriptor.set("enumerable", JsBoolean.of(flags.enumerable()));
        descriptor.set("configurable", JsBoolean.of(flags.configurable()));
        return descriptor;
    }

    private static JsValue arrayDescriptor(JsArray array, JsValue keyValue) {
        if (keyValue instanceof JsSymbol) {
            return JsUndefined.getInstance();
        }
        final var key = JsCoercion.toStr(keyValue);
        if ("length".equals(key)) {
            return dataDescriptor(new JsNumber(array.length()), array.getLengthFlags());
        }
        final var index = InterpreterUtils.arrayIndex(key);
        if (index != null) {
            if (array.hasIndexAccessor(index)) {
                final var getter = array.getIndexAccessorGetter(index);
                final var setter = array.getIndexAccessorSetter(index);
                final var flags = array.getIndexFlags(index);
                final var descriptor = new JsObject();
                descriptor.set("get", getter == null ? JsUndefined.getInstance() : getter);
                descriptor.set("set", setter == null ? JsUndefined.getInstance() : setter);
                descriptor.set("enumerable", JsBoolean.of(flags.enumerable()));
                descriptor.set("configurable", JsBoolean.of(flags.configurable()));
                return descriptor;
            }
            if (index >= array.length() || array.isHole(index)) {
                return JsUndefined.getInstance();
            }
            return dataDescriptor(array.get(index), array.getIndexFlags(index));
        }
        if (array.hasPropAccessor(key)) {
            final var getter = array.getPropAccessorGetter(key);
            final var setter = array.getPropAccessorSetter(key);
            final var flags = array.getPropFlags(key);
            final var descriptor = new JsObject();
            descriptor.set("get", getter == null ? JsUndefined.getInstance() : getter);
            descriptor.set("set", setter == null ? JsUndefined.getInstance() : setter);
            descriptor.set("enumerable", JsBoolean.of(flags.enumerable()));
            descriptor.set("configurable", JsBoolean.of(flags.configurable()));
            return descriptor;
        }
        if (!array.hasProperty(key)) {
            return JsUndefined.getInstance();
        }
        return dataDescriptor(array.getProperty(key), array.getPropFlags(key));
    }

    private static JsValue dataDescriptor(JsValue value, JsObject.PropertyFlags flags) {
        final var descriptor = new JsObject();
        descriptor.set("value", value);
        descriptor.set("writable", JsBoolean.of(flags.writable()));
        descriptor.set("enumerable", JsBoolean.of(flags.enumerable()));
        descriptor.set("configurable", JsBoolean.of(flags.configurable()));
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
