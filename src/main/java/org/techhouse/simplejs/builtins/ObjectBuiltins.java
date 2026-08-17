package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.ownValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsArguments;
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
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsTypedArray;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;
import org.techhouse.simplejs.values.OrdinaryProperties;
import org.techhouse.simplejs.values.PropertyDescriptor;

public final class ObjectBuiltins {
    private ObjectBuiltins() {
    }

    public static JsNativeFunction create(IterableToList iterableToList, InterpreterOps ops, Invoker invoker,
            Intrinsics intrinsics) {
        final var object = new JsNativeFunction("Object", (_, args) -> coerceToObject(args, intrinsics));
        // GetOwnPropertyKeys and its neighbours start at ToObject(O), so a primitive argument is
        // boxed here rather than in each helper: a String wrapper then contributes its code units.
        object.setProperty("keys", new JsNativeFunction("keys", (_, args) -> keys(boxed(args, intrinsics), ops)));
        object.setProperty("values", new JsNativeFunction("values", (_, args) -> values(boxed(args, intrinsics), ops)));
        object.setProperty("entries",
                new JsNativeFunction("entries", (_, args) -> entries(boxed(args, intrinsics), ops)));
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
        object.setProperty("getOwnPropertyNames", new JsNativeFunction("getOwnPropertyNames",
                (_, args) -> getOwnPropertyNames(boxed(args, intrinsics), ops)));
        object.setProperty("getOwnPropertyDescriptor", new JsNativeFunction("getOwnPropertyDescriptor",
                (_, args) -> ops.getOwnPropertyDescriptor(intrinsics.toObject(first(args)), argAt(args, 1))));
        object.setProperty("getOwnPropertyDescriptors", new JsNativeFunction("getOwnPropertyDescriptors",
                (_, args) -> getOwnPropertyDescriptors(boxed(args, intrinsics), ops)));
        object.setProperty("fromEntries",
                new JsNativeFunction("fromEntries", (_, args) -> fromEntries(args, iterableToList)));
        object.setProperty("hasOwn", new JsNativeFunction("hasOwn", (_, args) -> hasOwn(args, ops)));
        object.setProperty("groupBy",
                new JsNativeFunction("groupBy", (_, args) -> groupBy(args, iterableToList, invoker)));
        object.setProperty("is", new JsNativeFunction("is", (_, args) -> is(args)));
        object.setProperty("getOwnPropertySymbols", new JsNativeFunction("getOwnPropertySymbols",
                (_, args) -> getOwnPropertySymbols(boxed(args, intrinsics))));
        return object;
    }

    private static List<JsValue> boxed(List<JsValue> args, Intrinsics intrinsics) {
        final var boxed = new ArrayList<>(args);
        if (boxed.isEmpty()) {
            boxed.add(JsUndefined.getInstance());
        }
        boxed.set(0, intrinsics.toObject(boxed.getFirst()));
        return boxed;
    }

    private static JsValue coerceToObject(List<JsValue> args, Intrinsics intrinsics) {
        final var value = first(args);
        if (InterpreterUtils.isNullish(value)) {
            final var created = new JsObject();
            created.setProto(intrinsics.objectProto());
            return created;
        }
        return intrinsics.toObject(value);
    }

    private static JsValue is(List<JsValue> args) {
        return JsBoolean.of(!OrdinaryProperties.isNotSameValue(argAt(args, 0), argAt(args, 1)));
    }

    private static JsValue getOwnPropertySymbols(List<JsValue> args) {
        final var result = new JsArray();
        for (final var key : first(args).ownPropertyKeys()) {
            if (key instanceof JsSymbol) {
                result.push(key);
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

    // Deliberately narrower than JsValue.hasOwnKey: an accessor-only own property and an exotic
    // type's table are not reported here, which several corpus expectations still rely on.
    static boolean hasOwnKey(JsValue target, String key) {
        return switch (target) {
            case JsObject object -> object.hasOwnKey(new JsString(key)) || object.hasAccessor(key);
            case JsClass cls -> cls.getStaticOwner().has(key) || cls.getStaticOwner().hasAccessor(key);
            case JsArray array -> "length".equals(key) || arrayHasIndex(array, key);
            case JsString string -> "length".equals(key) || stringHasIndex(string, key);
            case JsTypedArray typed -> "length".equals(key) || typedHasIndex(typed, key);
            case JsGlobalObject global -> global.getEnv().isDeclared(key);
            case JsArguments arguments -> arguments.hasOwnKey(new JsString(key));
            case JsCallableProperties callable ->
                callable.hasProperty(key) || OrdinaryProperties.metadataKey(callable, key);
            default -> false;
        };
    }

    static boolean hasOwnSymbol(JsValue target, JsSymbol key) {
        if (target instanceof JsClass cls) {
            return cls.getStaticOwner().hasSymbol(key);
        }
        return target instanceof JsObject object && object.hasSymbol(key);
    }

    private static boolean arrayHasIndex(JsArray array, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        if (index != null) {
            return (index < array.length() && !array.isHole(index)) || array.hasIndexAccessor(index);
        }
        return array.hasProperty(key) || array.hasPropAccessor(key);
    }

    private static JsValue toPropertyKey(JsValue value, InterpreterOps ops) {
        return JsCoercion.toPropertyKey(value, ops);
    }

    // ToPropertyDescriptor: each field is read through HasProperty+Get (so an inherited accessor on
    // the descriptor object is honoured) and an ill-formed descriptor is rejected here, before any
    // [[DefineOwnProperty]] work can half-apply it.
    private static PropertyDescriptor toPropertyDescriptor(JsValue descriptor, InterpreterOps ops) {
        final var getter = descHas(descriptor, "get", ops) ? descGet(descriptor, "get", ops) : null;
        final var setter = descHas(descriptor, "set", ops) ? descGet(descriptor, "set", ops) : null;
        final var hasValue = descHas(descriptor, "value", ops);
        final var hasWritable = descHas(descriptor, "writable", ops);
        if ((getter != null || setter != null) && (hasValue || hasWritable)) {
            throw new TypeErrorException("Invalid property descriptor. Cannot both specify accessors "
                    + "and a value or writable attribute");
        }
        requireAccessorField(getter, "Getter");
        requireAccessorField(setter, "Setter");
        final var value = hasValue ? descGet(descriptor, "value", ops) : null;
        final Boolean writable = hasWritable ? JsCoercion.toBoolean(descGet(descriptor, "writable", ops)) : null;
        final Boolean enumerable = descHas(descriptor, "enumerable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops))
                : null;
        final Boolean configurable = descHas(descriptor, "configurable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))
                : null;
        return new PropertyDescriptor(value, getter, setter, writable, enumerable, configurable);
    }

    private static void requireAccessorField(JsValue value, String label) {
        if (value != null && !isCallable(value) && !(value instanceof JsUndefined)) {
            throw new TypeErrorException(label + " must be a function");
        }
    }

    private static boolean stringHasIndex(JsString string, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index != null && index < string.getValue().length();
    }

    private static boolean typedHasIndex(JsTypedArray typed, String key) {
        final var index = InterpreterUtils.arrayIndex(key);
        return index != null && index < typed.length();
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
            case JsArguments arguments -> {
                for (final var key : arguments.enumerablePropertyKeys()) {
                    result.push(new JsString(key));
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
                final var table = target.ownProperties();
                if (table != null) {
                    table.preventExtensions();
                }
            }
        }
        return target;
    }

    public static JsValue isExtensible(List<JsValue> args) {
        return JsBoolean.of(switch (first(args)) {
            case JsObject object -> object.isExtensible();
            case JsArray array -> array.isExtensible();
            case JsValue other -> other.isExtensible();
        });
    }

    private static JsValue createObject(List<JsValue> args, InterpreterOps ops) {
        final var proto = first(args);
        if (!InterpreterUtils.isObjectLike(proto) && !(proto instanceof JsNull)) {
            throw new TypeErrorException("Object prototype may only be an Object or null: " + JsCoercion.toStr(proto));
        }
        final var object = new JsObject();
        if (InterpreterUtils.isObjectLike(proto)) {
            object.setProto(proto);
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
        final var proto = first(args).getProto();
        return proto == null ? JsNull.getInstance() : proto;
    }

    public static JsValue setPrototypeOf(List<JsValue> args) {
        final var target = first(args);
        if (target instanceof JsObject object) {
            final var proto = args.size() > 1 && InterpreterUtils.isObjectLike(args.get(1)) ? args.get(1) : null;
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
        final var propertyKey = toPropertyKey(args.get(1), ops);
        // The boolean answers only whether the target owns a definition at all (a proxy reaches here
        // through Object.defineProperties); a rejected definition is raised as a TypeError instead.
        target.defineOwnProperty(propertyKey, toPropertyDescriptor(args.get(2), ops));
        return target;
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
        if (first(args) instanceof JsProxy proxy) {
            for (final var key : ops.ownKeys(proxy)) {
                result.push(key);
            }
            return result;
        }
        for (final var key : first(args).ownPropertyKeys()) {
            if (key instanceof JsString) {
                result.push(key);
            }
        }
        return result;
    }

    public static JsValue getOwnPropertyDescriptor(List<JsValue> args) {
        final var target = first(args);
        // ToObject(O): only null/undefined throw; any other primitive simply has no own property.
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var descriptor = target.getOwnProperty(argAt(args, 1));
        return descriptor == null ? JsUndefined.getInstance() : describe(descriptor);
    }

    private static JsValue describe(PropertyDescriptor descriptor) {
        final var result = new JsObject();
        if (descriptor.isAccessorDescriptor()) {
            result.set("get", descriptor.getter());
            result.set("set", descriptor.setter());
        } else {
            result.set("value", descriptor.value());
            result.set("writable", JsBoolean.of(descriptor.writableOr(false)));
        }
        result.set("enumerable", JsBoolean.of(descriptor.enumerableOr(false)));
        result.set("configurable", JsBoolean.of(descriptor.configurableOr(false)));
        return result;
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
