package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.interpreter.Iteration;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class MapBuiltins {
    public static final List<String> NAMES = List.of("get", "set", "has", "delete", "clear", "forEach", "keys",
            "values", "entries", "getOrInsert", "getOrInsertComputed");
    public static final List<String> WEAK_NAMES = List.of("get", "set", "has", "delete", "getOrInsert",
            "getOrInsertComputed");

    private MapBuiltins() {
    }

    public static JsNativeFunction create(IterableToList iterableToList, Invoker invoker, InterpreterOps ops,
            boolean weak) {
        final var name = weak ? "WeakMap" : "Map";
        final var constructor = new JsNativeFunction(name, (thisArg, args) -> {
            requireNewTarget(name, thisArg);
            return construct(args, ops, weak);
        });
        if (!weak) {
            constructor.setProperty("groupBy",
                    new JsNativeFunction("groupBy", (_, args) -> groupBy(args, iterableToList, invoker)));
        }
        return constructor;
    }

    // Reached without `new` there is no new.target; a subclass's super() call arrives with the
    // instance under construction as thisArg, which is what keeps `class M extends Map {}` working.
    private static void requireNewTarget(String name, JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if ((newTarget == null || newTarget instanceof JsUndefined) && thisArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor " + name + " requires 'new'");
        }
    }

    private static JsValue groupBy(List<JsValue> args, IterableToList iterableToList, Invoker invoker) {
        final var source = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var callback = args.size() > 1 ? args.get(1) : JsUndefined.getInstance();
        if (!InterpreterUtils.isCallable(callback)) {
            throw new TypeErrorException("Map.groupBy callbackfn is not a function");
        }
        final var map = new JsMap(false);
        final var items = iterableToList.drain(source);
        for (var i = 0; i < items.size(); i++) {
            final var key = invoker.call(callback, JsUndefined.getInstance(), List.of(items.get(i), new JsNumber(i)));
            final JsArray bucket;
            if (map.get(key) instanceof JsArray existing) {
                bucket = existing;
            } else {
                bucket = new JsArray();
                map.set(key, bucket);
            }
            bucket.push(items.get(i));
        }
        return map;
    }

    // AddEntriesFromIterable: the entries are pulled one at a time (the iterable may be endless), the
    // adder is read off the receiver so a patched `set` is honoured, and every abrupt completion after
    // the step closes the iterator before it propagates.
    private static JsValue construct(List<JsValue> args, InterpreterOps ops, boolean weak) {
        final var map = new JsMap(weak);
        final var iterable = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        if (iterable instanceof JsUndefined || iterable instanceof JsNull) {
            return map;
        }
        final var adder = ops.getMember(map, new JsString("set"));
        if (!InterpreterUtils.isCallable(adder)) {
            throw new TypeErrorException((weak ? "WeakMap" : "Map") + ".prototype.set is not a function");
        }
        new Iteration(ops, iterable).forEach(entry -> {
            if (!InterpreterUtils.isObjectLike(entry)) {
                throw new TypeErrorException("Iterator value is not an entry object");
            }
            final var key = ops.getMember(entry, new JsString("0"));
            final var value = ops.getMember(entry, new JsString("1"));
            ops.call(adder, map, List.of(key, value));
        });
        return map;
    }

    // `size` is an accessor on the prototype, not a data method, so reading its descriptor off
    // Map.prototype has to yield a getter and a foreign receiver has to throw rather than answer 0.
    public static void installSizeAccessor(JsObject proto, boolean weak) {
        final var label = weak ? "WeakMap" : "Map";
        final var getter = new JsNativeFunction("get size", (thisArg, _) -> {
            if (!(thisArg instanceof JsMap map) || map.isWeak() != weak) {
                throw new TypeErrorException(label + ".prototype.size called on an incompatible receiver");
            }
            return new JsNumber(map.size());
        });
        getter.setLength(0);
        proto.defineAccessor("size", getter, null);
        proto.setFlags("size", new JsObject.PropertyFlags(true, false, true));
    }

    public static JsValue getMethod(JsMap receiver, String name, Invoker invoker) {
        return switch (name) {
            case "size" -> new JsNumber(receiver.size());
            case "get" -> new JsNativeFunction("get", (_, args) -> receiver.get(arg(args, 0)));
            case "set" -> new JsNativeFunction("set", (_, args) -> {
                set(receiver, arg(args, 0), arg(args, 1));
                return receiver;
            });
            case "has" -> new JsNativeFunction("has", (_, args) -> JsBoolean.of(receiver.has(arg(args, 0))));
            case "delete" -> new JsNativeFunction("delete", (_, args) -> JsBoolean.of(receiver.delete(arg(args, 0))));
            case "clear" -> new JsNativeFunction("clear", (_, _) -> {
                receiver.clear();
                return JsUndefined.getInstance();
            });
            case "getOrInsert" ->
                new JsNativeFunction("getOrInsert", (_, args) -> getOrInsert(receiver, arg(args, 0), arg(args, 1)));
            case "getOrInsertComputed" -> new JsNativeFunction("getOrInsertComputed",
                    (_, args) -> getOrInsertComputed(receiver, arg(args, 0), arg(args, 1), invoker));
            case "forEach" -> new JsNativeFunction("forEach", (_, args) -> forEach(receiver, args, invoker));
            case "keys" -> new JsNativeFunction("keys", (_, _) -> keysIterator(receiver));
            case "values" -> new JsNativeFunction("values", (_, _) -> valuesIterator(receiver));
            case "entries" -> new JsNativeFunction("entries", (_, _) -> entriesIterator(receiver));
            default -> null;
        };
    }

    private static JsValue getOrInsert(JsMap map, JsValue key, JsValue value) {
        final var canonical = canonicalize(key);
        requireValidKey(map, canonical);
        if (map.has(canonical)) {
            return map.get(canonical);
        }
        map.set(canonical, value);
        return value;
    }

    private static JsValue getOrInsertComputed(JsMap map, JsValue key, JsValue callback, Invoker invoker) {
        final var canonical = canonicalize(key);
        requireValidKey(map, canonical);
        if (!InterpreterUtils.isCallable(callback)) {
            throw new TypeErrorException("getOrInsertComputed callbackfn is not a function");
        }
        if (map.has(canonical)) {
            return map.get(canonical);
        }
        final var value = invoker.call(callback, JsUndefined.getInstance(), List.of(canonical));
        map.set(canonical, value);
        return value;
    }

    private static void requireValidKey(JsMap map, JsValue key) {
        if (map.isWeak() && isNotObjectKey(key)) {
            throw new TypeErrorException("Invalid value used as weak map key");
        }
    }

    private static JsValue canonicalize(JsValue key) {
        return key instanceof JsNumber number && number.getValue() == 0 ? new JsNumber(0) : key;
    }

    public static void set(JsMap map, JsValue key, JsValue value) {
        if (map.isWeak() && isNotObjectKey(key)) {
            throw new TypeErrorException("Invalid value used as weak map key");
        }
        map.set(key, value);
    }

    // The three iterators and forEach all walk a live [[MapData]] cursor rather than a snapshot, so an
    // entry added, deleted or re-added by the consumer is observed exactly as the spec prescribes.
    public static JsObject entriesIterator(JsMap map) {
        final var cursor = map.cursor();
        return JsIterators.lazy(_ -> {
            final var entry = cursor.next();
            return entry == null ? null : new JsArray(new ArrayList<>(List.of(entry.key(), entry.value())));
        });
    }

    private static JsObject keysIterator(JsMap map) {
        final var cursor = map.cursor();
        return JsIterators.lazy(_ -> {
            final var entry = cursor.next();
            return entry == null ? null : entry.key();
        });
    }

    private static JsObject valuesIterator(JsMap map) {
        final var cursor = map.cursor();
        return JsIterators.lazy(_ -> {
            final var entry = cursor.next();
            return entry == null ? null : entry.value();
        });
    }

    private static JsValue forEach(JsMap map, List<JsValue> args, Invoker invoker) {
        final var callback = arg(args, 0);
        if (!InterpreterUtils.isCallable(callback)) {
            throw new TypeErrorException("Map.prototype.forEach callbackfn is not a function");
        }
        final var thisArg = arg(args, 1);
        final var cursor = map.cursor();
        for (var entry = cursor.next(); entry != null; entry = cursor.next()) {
            invoker.call(callback, thisArg, List.of(entry.value(), entry.key(), map));
        }
        return JsUndefined.getInstance();
    }

    private static boolean isNotObjectKey(JsValue value) {
        return !switch (value) {
            case JsNumber ignored -> false;
            case JsString ignored -> false;
            case JsBoolean ignored -> false;
            case org.techhouse.simplejs.values.JsBigInt ignored -> false;
            case JsNull ignored -> false;
            case JsUndefined ignored -> false;
            default -> true;
        };
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
