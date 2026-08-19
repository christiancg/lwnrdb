package org.techhouse.simplejs.builtins;

import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.ownValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.interpreter.Iteration;
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
import org.techhouse.simplejs.values.PropertyTable;

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
        object.setProperty("assign", new JsNativeFunction("assign", (_, args) -> assign(args, ops, intrinsics)));
        object.setProperty("freeze", new JsNativeFunction("freeze", (_, args) -> setIntegrityLevel(args, ops, true)));
        object.setProperty("isFrozen",
                new JsNativeFunction("isFrozen", (_, args) -> testIntegrityLevel(args, ops, true)));
        object.setProperty("seal", new JsNativeFunction("seal", (_, args) -> setIntegrityLevel(args, ops, false)));
        object.setProperty("isSealed",
                new JsNativeFunction("isSealed", (_, args) -> testIntegrityLevel(args, ops, false)));
        object.setProperty("preventExtensions", new JsNativeFunction("preventExtensions", (_, args) -> {
            if (InterpreterUtils.isObjectLike(first(args)) && !ops.preventExtensions(first(args))) {
                throw new TypeErrorException("Cannot prevent extensions");
            }
            return first(args);
        }));
        object.setProperty("isExtensible",
                new JsNativeFunction("isExtensible", (_, args) -> JsBoolean.of(ops.isExtensible(first(args)))));
        object.setProperty("create", new JsNativeFunction("create", (_, args) -> createObject(args, ops, intrinsics)));
        object.setProperty("getPrototypeOf", new JsNativeFunction("getPrototypeOf", (_, args) -> {
            if (InterpreterUtils.isNullish(first(args))) {
                throw new TypeErrorException("Cannot convert undefined or null to object");
            }
            return ops.getPrototypeOf(intrinsics.toObject(first(args)));
        }));
        object.setProperty("setPrototypeOf", new JsNativeFunction("setPrototypeOf", (_, args) -> {
            // [[SetPrototypeOf]] can fail without throwing (a Proxy trap answering false, a
            // non-extensible target, ...); Object.setPrototypeOf must turn that false into a
            // TypeError instead of silently reporting success.
            if (!ops.setPrototypeOf(first(args), argAt(args, 1))) {
                throw new TypeErrorException("Object.setPrototypeOf: trap returned falsish for property '"
                        + JsCoercion.toStr(argAt(args, 1)) + "'");
            }
            return first(args);
        }));
        object.setProperty("defineProperty", new JsNativeFunction("defineProperty", (_, args) -> {
            // Same rejection-without-throw case as setPrototypeOf above: [[DefineOwnProperty]]
            // (notably a Proxy's trap) can answer false, which Object.defineProperty must reject.
            if (!ops.defineProperty(first(args), argAt(args, 1), argAt(args, 2))) {
                throw new TypeErrorException("Object.defineProperty: trap returned falsish for property '"
                        + JsCoercion.toStr(argAt(args, 1)) + "'");
            }
            return first(args);
        }));
        object.setProperty("defineProperties",
                new JsNativeFunction("defineProperties", (_, args) -> defineProperties(args, ops)));
        object.setProperty("getOwnPropertyNames", new JsNativeFunction("getOwnPropertyNames",
                (_, args) -> getOwnPropertyNames(boxed(args, intrinsics), ops)));
        object.setProperty("getOwnPropertyDescriptor",
                new JsNativeFunction("getOwnPropertyDescriptor",
                        (_, args) -> attachObjectProto(
                                ops.getOwnPropertyDescriptor(intrinsics.toObject(first(args)), argAt(args, 1)),
                                intrinsics)));
        object.setProperty("getOwnPropertyDescriptors", new JsNativeFunction("getOwnPropertyDescriptors",
                (_, args) -> getOwnPropertyDescriptors(boxed(args, intrinsics), ops, intrinsics)));
        object.setProperty("fromEntries",
                new JsNativeFunction("fromEntries", (_, args) -> fromEntries(args, ops, intrinsics)));
        object.setProperty("hasOwn", new JsNativeFunction("hasOwn", (_, args) -> hasOwn(args, ops)));
        object.setProperty("groupBy",
                new JsNativeFunction("groupBy", (_, args) -> groupBy(args, iterableToList, invoker, ops)));
        object.setProperty("is", new JsNativeFunction("is", (_, args) -> is(args)));
        object.setProperty("getOwnPropertySymbols", new JsNativeFunction("getOwnPropertySymbols",
                (_, args) -> getOwnPropertySymbols(boxed(args, intrinsics), ops)));
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

    // Routed through ops.ownKeys rather than target.ownPropertyKeys() so a Proxy's ownKeys trap
    // (and its result-invariant validation) is consulted instead of silently answering empty - a
    // Proxy has no PropertyTable of its own (ProxyDispatch intercepts ahead of it).
    private static JsValue getOwnPropertySymbols(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        for (final var key : ops.ownKeys(first(args))) {
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
            return JsBoolean.of(hasOwnSymbol(args.getFirst(), symbol, ops));
        }
        return JsBoolean.of(hasOwnKey(args.getFirst(), JsCoercion.toStr(key), ops));
    }

    // A Proxy is answered through its [[GetOwnProperty]] trap (via ops), since it carries no
    // PropertyTable of its own for the generic fallback below to find. Every other exotic type
    // (JsRegExp, JsDate, JsMap, JsSet, JsPromise, buffers/views, generators...) that isn't given
    // its own arm here still has a real table behind ownProperties(), so the fallback answers from
    // that instead of hard-coding false - which is what let an ad hoc `sample.foo = true` go
    // unreported by hasOwnProperty even though getOwnPropertyNames listed it.
    static boolean hasOwnKey(JsValue target, String key, InterpreterOps ops) {
        return switch (target) {
            case JsProxy proxy ->
                ops != null && !(ops.getOwnPropertyDescriptor(proxy, new JsString(key)) instanceof JsUndefined);
            // A builtin-subclass instance (`class R extends RegExp {}`) wraps its native state as
            // `object.getPrimitive()` rather than holding it in the instance's own table (see
            // MemberEvaluator's getMember delegation for the read side), so an own key that lives on
            // the wrapped primitive (e.g. RegExp's own `lastIndex`) must be consulted there too.
            case JsObject object -> object.hasOwnKey(new JsString(key)) || object.hasAccessor(key)
                    || (object.getPrimitive() != null && hasOwnKey(object.getPrimitive(), key, ops));
            case JsClass cls -> cls.getStaticOwner().has(key) || cls.getStaticOwner().hasAccessor(key);
            case JsArray array -> "length".equals(key) || arrayHasIndex(array, key);
            case JsString string -> "length".equals(key) || stringHasIndex(string, key);
            case JsTypedArray typed ->
                "length".equals(key) || typedHasIndex(typed, key) || hasTableAccessor(target, key)
                        || (target.ownProperties() != null && target.ownProperties().has(key));
            case JsGlobalObject global ->
                global.getEnv().hasGlobalProperty(key) || global.ownProperties().hasAccessor(key);
            case JsArguments arguments -> arguments.hasOwnKey(new JsString(key));
            case JsCallableProperties callable -> callable.hasProperty(key)
                    || OrdinaryProperties.metadataKey(callable, key) || hasTableAccessor(target, key);
            default -> target.hasOwnKey(new JsString(key));
        };
    }

    private static boolean hasTableAccessor(JsValue target, String key) {
        final var table = target.ownProperties();
        return table != null && table.hasAccessor(key);
    }

    static boolean hasOwnSymbol(JsValue target, JsSymbol key, InterpreterOps ops) {
        if (target instanceof JsProxy proxy) {
            return ops != null && !(ops.getOwnPropertyDescriptor(proxy, key) instanceof JsUndefined);
        }
        final var table = symbolTableOf(target);
        return table != null && (table.hasSymbol(key) || table.hasSymbolAccessor(key));
    }

    static boolean isEnumerableOwnSymbol(JsValue target, JsSymbol key) {
        final var table = symbolTableOf(target);
        return table != null && table.getSymbolFlags(key).enumerable();
    }

    private static PropertyTable symbolTableOf(JsValue target) {
        return target instanceof JsClass cls ? cls.getStaticOwner().ownProperties() : target.ownProperties();
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
        // Field order is normative: a poisoned accessor on the descriptor object must be observed in
        // enumerable, configurable, value, writable, get, set order.
        final Boolean enumerable = descHas(descriptor, "enumerable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "enumerable", ops))
                : null;
        final Boolean configurable = descHas(descriptor, "configurable", ops)
                ? JsCoercion.toBoolean(descGet(descriptor, "configurable", ops))
                : null;
        final var hasValue = descHas(descriptor, "value", ops);
        final var value = hasValue ? descGet(descriptor, "value", ops) : null;
        final var hasWritable = descHas(descriptor, "writable", ops);
        final Boolean writable = hasWritable ? JsCoercion.toBoolean(descGet(descriptor, "writable", ops)) : null;
        final var getter = descHas(descriptor, "get", ops) ? descGet(descriptor, "get", ops) : null;
        requireAccessorField(getter, "Getter");
        final var setter = descHas(descriptor, "set", ops) ? descGet(descriptor, "set", ops) : null;
        requireAccessorField(setter, "Setter");
        if ((getter != null || setter != null) && (hasValue || hasWritable)) {
            throw new TypeErrorException("Invalid property descriptor. Cannot both specify accessors "
                    + "and a value or writable attribute");
        }
        return new PropertyDescriptor(value, getter, setter, writable, enumerable, configurable);
    }

    private static void requireAccessorField(JsValue value, String label) {
        if (value != null && isNotCallable(value) && !(value instanceof JsUndefined)) {
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

    // GroupBy(items, callbackfn, property): items must be object-coercible and callbackfn callable
    // before any iteration happens, and the bucket key goes through the real ToPropertyKey (which can
    // yield a Symbol, and can throw on a poisoned toString/valueOf) rather than a raw toStr.
    private static JsValue groupBy(List<JsValue> args, IterableToList iterableToList, Invoker invoker,
            InterpreterOps ops) {
        final var source = first(args);
        if (InterpreterUtils.isNullish(source)) {
            throw new TypeErrorException("Object.groupBy requires that the first argument not be null or undefined");
        }
        final var callback = argAt(args, 1);
        if (isNotCallable(callback)) {
            throw new TypeErrorException("Object.groupBy: callback is not a function");
        }
        final var result = new JsObject();
        // Object.groupBy is OrdinaryObjectCreate(null): the result has no prototype at all.
        result.setProto(null);
        final var items = source instanceof JsArray array ? array.getElements() : iterableToList.drain(source);
        for (var i = 0; i < items.size(); i++) {
            final var rawKey = invoker.call(callback, JsUndefined.getInstance(),
                    List.of(items.get(i), new JsNumber(i)));
            final var propertyKey = toPropertyKey(rawKey, ops);
            groupByBucket(result, propertyKey).push(items.get(i));
        }
        return result;
    }

    private static JsArray groupByBucket(JsObject result, JsValue propertyKey) {
        if (propertyKey instanceof JsSymbol symbol) {
            if (result.getSymbol(symbol) instanceof JsArray existing) {
                return existing;
            }
            final var bucket = new JsArray();
            result.setSymbol(symbol, bucket);
            return bucket;
        }
        final var key = JsCoercion.toStr(propertyKey);
        if (result.get(key) instanceof JsArray existing) {
            return existing;
        }
        final var bucket = new JsArray();
        result.defineValue(key, bucket);
        return bucket;
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
                    if ((object.has(key) || object.hasAccessor(key)) && object.isEnumerable(key)) {
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
                for (final var key : orderedEnumerableCallableKeys(callable)) {
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
                // EnumerableOwnPropertyNames(kind=value): GetOwnProperty then, if enumerable, Get -
                // interleaved per key (not a getOwnPropertyDescriptor batch followed by a get batch).
                for (final var key : ops.ownKeys(proxy)) {
                    if (key instanceof JsString && isEnumerableOwnKey(proxy, key, ops)) {
                        result.push(ops.getMember(proxy, key));
                    }
                }
            }
            case JsObject object -> {
                for (final var key : object.keys()) {
                    if ((object.has(key) || object.hasAccessor(key)) && object.isEnumerable(key)) {
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
                for (final var key : orderedEnumerableCallableKeys(callable)) {
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
                for (final var key : ops.ownKeys(proxy)) {
                    if (key instanceof JsString string && isEnumerableOwnKey(proxy, key, ops)) {
                        result.push(new JsArray(List.of(string, ops.getMember(proxy, key))));
                    }
                }
            }
            case JsObject object -> {
                for (final var key : object.keys()) {
                    if ((object.has(key) || object.hasAccessor(key)) && object.isEnumerable(key)) {
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
                for (final var key : orderedEnumerableCallableKeys(callable)) {
                    result.push(new JsArray(List.of(new JsString(key), callable.getProperty(key))));
                }
            }
            default -> {
            }
        }
        return result;
    }

    // A function's length/name/prototype are synthesised into its table lazily, on first touch
    // (e.g. a defineProperty redefine) rather than at creation - so JsFunction/JsNativeFunction's
    // enumerablePropertyKeys(), which just filters the table in physical insertion order, can report
    // one materialised late (after a user-added key) in the wrong position. OrdinaryOwnPropertyKeys
    // treats these as created before any own property a script adds, so once redefined enumerable
    // they must sort to the front (in their fixed length/name/prototype order) ahead of everything
    // else, which keeps its existing relative order.
    private static final List<String> METADATA_KEY_ORDER = List.of("length", "name", "prototype");

    private static List<String> orderedEnumerableCallableKeys(JsCallableProperties callable) {
        final var raw = callable.enumerablePropertyKeys();
        final var ordered = new ArrayList<String>(raw.size());
        for (final var metaKey : METADATA_KEY_ORDER) {
            if (raw.contains(metaKey)) {
                ordered.add(metaKey);
            }
        }
        for (final var key : raw) {
            if (!METADATA_KEY_ORDER.contains(key)) {
                ordered.add(key);
            }
        }
        return ordered;
    }

    // Object.assign, spec-general rather than special-cased per source/target shape: ToObject the
    // target (a primitive target must come back wrapped and typeof "object"), then for every
    // source walk its real [[OwnPropertyKeys]]/[[GetOwnProperty]]/[[Get]] through the ops seam -
    // which is what makes a String source's index characters, an Array source/target's exotic
    // length semantics, and a Proxy source's traps (including one that throws) all fall out for
    // free instead of needing their own branch. The [[Set]] on the target honours an inherited
    // setter and throws when the write is rejected (non-writable, non-extensible), per Set(...,
    // throwOnFailure=true).
    private static JsValue assign(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
        final var to = intrinsics.toObject(first(args));
        for (var i = 1; i < args.size(); i++) {
            final var sourceArg = argAt(args, i);
            if (InterpreterUtils.isNullish(sourceArg)) {
                continue;
            }
            final var from = intrinsics.toObject(sourceArg);
            for (final var key : ops.ownKeys(from)) {
                if (!(ops.getOwnPropertyDescriptor(from, key) instanceof JsObject descriptor)
                        || !JsCoercion.toBoolean(descriptor.get("enumerable"))) {
                    continue;
                }
                final var value = ops.getMember(from, key);
                if (!ops.setMember(to, key, value)) {
                    throw new TypeErrorException(
                            "Cannot assign to read only property '" + JsCoercion.toStr(key) + "' of object");
                }
            }
        }
        return to;
    }

    // SetIntegrityLevel: extensibility is dropped first, then every own property is redefined
    // through [[DefineOwnProperty]] - so an exotic key set (an array's indices, a native function's
    // statics) and a proxy's traps are covered by the same walk.
    private static JsValue setIntegrityLevel(List<JsValue> args, InterpreterOps ops, boolean frozen) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            return target;
        }
        if (!ops.preventExtensions(target)) {
            throw new TypeErrorException("Cannot prevent extensions");
        }
        for (final var key : ops.ownKeys(target)) {
            final var current = ops.getOwnPropertyDescriptor(target, key);
            if (!(current instanceof JsObject descriptor)) {
                continue;
            }
            if (!ops.defineProperty(target, key, integrityDescriptor(descriptor, frozen))) {
                throw new TypeErrorException("Cannot redefine property: " + JsCoercion.toStr(key));
            }
        }
        return target;
    }

    // A data property loses [[Writable]] too; an accessor has none to lose, and asking for
    // writable:false on one would be rejected as an incompatible redefinition.
    private static JsValue integrityDescriptor(JsObject current, boolean frozen) {
        final var descriptor = new JsObject();
        descriptor.set("configurable", JsBoolean.FALSE);
        if (frozen && !current.has("get") && !current.has("set")) {
            descriptor.set("writable", JsBoolean.FALSE);
        }
        return descriptor;
    }

    // TestIntegrityLevel: an extensible object is never sealed or frozen, whatever its properties
    // say - so the extensibility check comes before the property walk, not after it.
    private static JsValue testIntegrityLevel(List<JsValue> args, InterpreterOps ops, boolean frozen) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            return JsBoolean.TRUE;
        }
        if (ops.isExtensible(target)) {
            return JsBoolean.FALSE;
        }
        for (final var key : ops.ownKeys(target)) {
            if (!(ops.getOwnPropertyDescriptor(target, key) instanceof JsObject descriptor)) {
                continue;
            }
            if (JsCoercion.toBoolean(descriptor.get("configurable"))) {
                return JsBoolean.FALSE;
            }
            if (frozen && descriptor.has("writable") && JsCoercion.toBoolean(descriptor.get("writable"))) {
                return JsBoolean.FALSE;
            }
        }
        return JsBoolean.TRUE;
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

    private static JsValue createObject(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
        final var proto = first(args);
        if (!InterpreterUtils.isObjectLike(proto) && !(proto instanceof JsNull)) {
            throw new TypeErrorException("Object prototype may only be an Object or null: " + JsCoercion.toStr(proto));
        }
        final var object = new JsObject();
        // Always routed through setProto - even for the null case - so the object is marked as
        // deliberately proto-less rather than merely "never resolved" (see JsObject.setProto).
        object.setProto(InterpreterUtils.isObjectLike(proto) ? proto : null);
        if (args.size() > 1 && !(args.get(1) instanceof JsUndefined)) {
            if (args.get(1) instanceof JsNull) {
                throw new TypeErrorException("Cannot convert undefined or null to object");
            }
            // ObjectDefineProperties: Let props be ? ToObject(Properties) - a primitive (e.g. a
            // non-empty string) must be boxed so its real index/length own properties are what gets
            // walked, not silently skipped.
            applyPropertiesFrom(object, intrinsics.toObject(args.get(1)), ops);
        }
        return object;
    }

    public static JsValue getPrototypeOf(List<JsValue> args) {
        final var proto = first(args).getProto();
        return proto == null ? JsNull.getInstance() : proto;
    }

    public static JsValue setPrototypeOf(List<JsValue> args) {
        final var target = first(args);
        // RequireObjectCoercible(O), then the Type(proto) check - both run before step 4 asks
        // whether O is even an object, so a primitive target still rejects a bad proto argument.
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Object.setPrototypeOf called on null or undefined");
        }
        final var protoArg = argAt(args, 1);
        if (!InterpreterUtils.isObjectLike(protoArg) && !(protoArg instanceof JsNull)) {
            throw new TypeErrorException(
                    "Object prototype may only be an Object or null: " + JsCoercion.toStr(protoArg));
        }
        if (InterpreterUtils.isObjectLike(target)) {
            final var proto = InterpreterUtils.isObjectLike(protoArg) ? protoArg : null;
            // OrdinarySetPrototypeOf step 2: SameValue(V, current) is a no-op even when the target
            // is non-extensible.
            if (proto != target.getProto()) {
                // Step 4: rejected before the cyclic walk, which is otherwise the only signal a
                // non-extensible target ever gives - it would silently "succeed" without this.
                if (!target.isExtensible()) {
                    throw new TypeErrorException("Object.setPrototypeOf called on non-extensible object");
                }
                // Step 8: a cycle would make every later chain walk unbounded.
                for (var walk = proto; walk != null; walk = walk.getProto()) {
                    if (walk == target) {
                        throw new TypeErrorException("Cyclic __proto__ value");
                    }
                }
                target.setProto(proto);
            }
        }
        return target;
    }

    public static JsValue defineProperty(List<JsValue> args, InterpreterOps ops) {
        final var target = first(args);
        if (!InterpreterUtils.isObjectLike(target)) {
            throw new TypeErrorException("Object.defineProperty called on non-object");
        }
        // ToPropertyKey runs before the descriptor is even looked at, so a poisoned key coercion is
        // observed first and a non-object descriptor is only rejected afterwards.
        final var propertyKey = toPropertyKey(args.get(1), ops);
        if (args.size() < 3 || !InterpreterUtils.isObjectLike(args.get(2))) {
            throw new TypeErrorException("Property description must be an object");
        }
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
    // ObjectDefineProperties step 4 walks every own key (string AND symbol - a symbol-named
    // descriptor bag entry defines a symbol-keyed property just fine) and calls [[GetOwnProperty]]
    // on *each one*, in key order, before checking [[Enumerable]] - that GetOwnProperty call is
    // observable (e.g. through a Proxy's "getOwnPropertyDescriptor" trap), so a key must never be
    // skipped ahead of it on the sole basis of its type.
    private static void applyPropertiesFrom(JsValue target, JsValue props, InterpreterOps ops) {
        for (final var key : ops.ownKeys(props)) {
            if (!isEnumerableOwnKey(props, key, ops)) {
                continue;
            }
            defineProperty(List.of(target, key, ops.getMember(props, key)), ops);
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

    private static boolean isNotCallable(JsValue value) {
        return !(value instanceof JsFunction) && !(value instanceof JsNativeFunction);
    }

    private static JsValue getOwnPropertyDescriptors(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
        final var target = first(args);
        if (InterpreterUtils.isNullish(target)) {
            throw new TypeErrorException("Cannot convert undefined or null to object");
        }
        final var result = new JsObject();
        result.setProto(intrinsics.objectProto());
        for (final var key : ops.ownKeys(target)) {
            final var descriptor = attachObjectProto(ops.getOwnPropertyDescriptor(target, key), intrinsics);
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
                result.setSymbol(symbol, attachObjectProto(ops.getOwnPropertyDescriptor(target, symbol), intrinsics));
            }
        }
        return result;
    }

    // FromPropertyDescriptor builds a descriptor object linked to %Object.prototype% - the shared
    // static getOwnPropertyDescriptor(List) below has no Intrinsics access (it is also called from
    // the InterpreterOps seam, which this file does not own), so callers that do have an Intrinsics
    // reference patch the link in afterwards instead of leaving it proto-less.
    private static JsValue attachObjectProto(JsValue value, Intrinsics intrinsics) {
        if (value instanceof JsObject object && object.getProto() == null) {
            object.setProto(intrinsics.objectProto());
        }
        return value;
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
        final var key = argAt(args, 1);
        var descriptor = target.getOwnProperty(key);
        // A builtin-subclass instance's own state (e.g. RegExp's `lastIndex`) lives on the wrapped
        // primitive rather than the instance's own table; see hasOwnKey's matching fallback.
        if (descriptor == null && target instanceof JsObject wrapper && wrapper.getPrimitive() != null) {
            descriptor = wrapper.getPrimitive().getOwnProperty(key);
        }
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

    // AddEntriesFromIterable, with the CreateDataPropertyOrThrow adder inlined (Object.fromEntries'
    // own closure): GetIterator, then per step Get "0"/"1" off the entry (never iterate it - a
    // string/array entry's own @@iterator must not be touched) and only then ToPropertyKey the
    // result - in that exact order, since it is externally observable. A non-object entry, or an
    // abrupt completion from any of those three reads, closes the iterator via Iteration.forEach's
    // own IteratorClose-on-throw semantics before the original error propagates.
    private static JsValue fromEntries(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
        final var source = first(args);
        if (InterpreterUtils.isNullish(source)) {
            throw new TypeErrorException(
                    "Object.fromEntries requires that the first argument not be null or undefined");
        }
        final var result = new JsObject();
        result.setProto(intrinsics.objectProto());
        final var zero = new JsString("0");
        final var one = new JsString("1");
        new Iteration(ops, source).forEach(entry -> {
            if (!InterpreterUtils.isObjectLike(entry)) {
                throw new TypeErrorException("Iterator value " + JsCoercion.toStr(entry) + " is not an entry object");
            }
            final var key = ops.getMember(entry, zero);
            final var value = ops.getMember(entry, one);
            final var propertyKey = toPropertyKey(key, ops);
            if (propertyKey instanceof JsSymbol symbol) {
                result.setSymbol(symbol, value);
            } else {
                result.set(JsCoercion.toStr(propertyKey), value);
            }
        });
        return result;
    }

    private static JsValue first(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    private static JsValue argAt(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
