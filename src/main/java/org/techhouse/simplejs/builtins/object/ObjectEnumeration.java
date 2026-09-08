package org.techhouse.simplejs.builtins.object;

import static org.techhouse.simplejs.builtins.ObjectBuiltins.argAt;
import static org.techhouse.simplejs.builtins.ObjectBuiltins.first;
import static org.techhouse.simplejs.builtins.ObjectBuiltins.isNotCallable;
import static org.techhouse.simplejs.builtins.object.ObjectDescriptors.isEnumerableOwnKey;
import static org.techhouse.simplejs.builtins.object.ObjectDescriptors.toPropertyKey;
import static org.techhouse.simplejs.builtins.object.ObjectOwnKeys.enumerableProxyStringKeys;
import static org.techhouse.simplejs.internal.interpreter.InterpreterUtils.ownValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.techhouse.simplejs.builtins.InterpreterOps;
import org.techhouse.simplejs.builtins.Intrinsics;
import org.techhouse.simplejs.builtins.Invoker;
import org.techhouse.simplejs.builtins.IterableToList;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.interpreter.Iteration;
import org.techhouse.simplejs.values.JsArguments;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsCallableProperties;
import org.techhouse.simplejs.values.JsClass;
import org.techhouse.simplejs.values.JsGlobalObject;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsProxy;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ObjectEnumeration {
    public static JsValue groupBy(List<JsValue> args, IterableToList iterableToList, Invoker invoker,
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

    public static JsArray groupByBucket(JsObject result, JsValue propertyKey) {
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

    public static void chargeEnumeration(JsValue target, int perKey, InterpreterOps ops) {
        final var count = switch (target) {
            case JsArray array -> array.length() + array.namedPropertyKeys().size();
            case JsObject object -> object.keys().size();
            default -> 0L;
        };
        InterpreterOps.chargeElements(ops, count * perKey);
    }

    public static JsValue keys(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        chargeEnumeration(first(args), 1, ops);
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

    public static JsValue values(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        chargeEnumeration(first(args), 1, ops);
        switch (first(args)) {
            case JsProxy proxy -> {
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

    public static JsValue entries(List<JsValue> args, InterpreterOps ops) {
        final var result = new JsArray();
        chargeEnumeration(first(args), 3, ops);
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

    public static final List<String> METADATA_KEY_ORDER = List.of("length", "name", "prototype");

    public static List<String> orderedEnumerableCallableKeys(JsCallableProperties callable) {
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

    public static JsValue assign(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
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

    public static JsValue fromEntries(List<JsValue> args, InterpreterOps ops, Intrinsics intrinsics) {
        final var source = first(args);
        if (InterpreterUtils.isNullish(source)) {
            throw new TypeErrorException(
                    "Object.fromEntries requires that the first argument not be null or undefined");
        }
        final var result = new JsObject();
        result.setProto(intrinsics.objectProto);
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

    private ObjectEnumeration() {
    }
}
