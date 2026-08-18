package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.RangeErrorException;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.internal.interpreter.Iteration;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBigInt;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsSet;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class SetBuiltins {
    public static final List<String> NAMES = List.of("add", "has", "delete", "clear", "forEach", "keys", "values",
            "entries", "union", "intersection", "difference", "symmetricDifference", "isSubsetOf", "isSupersetOf",
            "isDisjointFrom");
    public static final List<String> WEAK_NAMES = List.of("add", "has", "delete");

    private SetBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops, boolean weak) {
        final var name = weak ? "WeakSet" : "Set";
        return new JsNativeFunction(name, (thisArg, args) -> {
            requireNewTarget(name, thisArg);
            return construct(args, ops, weak);
        });
    }

    // Reached without `new` there is no new.target; a subclass's super() call arrives with the
    // instance under construction as thisArg, which is what keeps `class S extends Set {}` working.
    private static void requireNewTarget(String name, JsValue thisArg) {
        final var newTarget = JsNativeFunction.currentNewTarget();
        if ((newTarget == null || newTarget instanceof JsUndefined) && thisArg instanceof JsUndefined) {
            throw new TypeErrorException("Constructor " + name + " requires 'new'");
        }
    }

    // The values are pulled one at a time (the iterable may be endless), the adder is read off the
    // receiver so a patched `add` is honoured, and an abrupt completion from it closes the iterator
    // before it propagates.
    private static JsValue construct(List<JsValue> args, InterpreterOps ops, boolean weak) {
        final var set = new JsSet(weak);
        final var iterable = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        if (iterable instanceof JsUndefined || iterable instanceof JsNull) {
            return set;
        }
        final var adder = ops.getMember(set, new JsString("add"));
        if (!InterpreterUtils.isCallable(adder)) {
            throw new TypeErrorException((weak ? "WeakSet" : "Set") + ".prototype.add is not a function");
        }
        new Iteration(ops, iterable).forEach(value -> ops.call(adder, set, List.of(value)));
        return set;
    }

    // `size` is an accessor on the prototype rather than a data method, and `keys` is not a method of
    // its own at all: it is the very same function object as `values`, which a script can compare.
    public static void installAccessors(JsObject proto, boolean weak) {
        final var label = weak ? "WeakSet" : "Set";
        final var getter = new JsNativeFunction("get size", (thisArg, _) -> {
            if (!(thisArg instanceof JsSet set) || set.isWeak() != weak) {
                throw new TypeErrorException(label + ".prototype.size called on an incompatible receiver");
            }
            return new JsNumber(set.size());
        });
        getter.setLength(0);
        proto.defineAccessor("size", getter, null);
        proto.setFlags("size", new JsObject.PropertyFlags(true, false, true));
        final var values = proto.get("values");
        if (values != null) {
            Intrinsics.installMethod(proto, "keys", values);
        }
    }

    public static JsValue getMethod(JsSet receiver, String name, Invoker invoker, InterpreterOps ops) {
        return switch (name) {
            case "size" -> new JsNumber(receiver.size());
            case "add" -> new JsNativeFunction("add", (_, args) -> {
                add(receiver, arg(args, 0));
                return receiver;
            });
            case "has" -> new JsNativeFunction("has", (_, args) -> JsBoolean.of(receiver.has(arg(args, 0))));
            case "delete" -> new JsNativeFunction("delete", (_, args) -> JsBoolean.of(receiver.delete(arg(args, 0))));
            case "clear" -> new JsNativeFunction("clear", (_, _) -> {
                receiver.clear();
                return JsUndefined.getInstance();
            });
            case "forEach" -> new JsNativeFunction("forEach", (_, args) -> forEach(receiver, args, invoker));
            case "keys", "values" -> new JsNativeFunction(name, (_, _) -> valuesIterator(receiver));
            case "entries" -> new JsNativeFunction("entries", (_, _) -> entriesIterator(receiver));
            case "union" -> new JsNativeFunction("union", (_, args) -> union(receiver, record(args, ops)));
            case "intersection" ->
                new JsNativeFunction("intersection", (_, args) -> intersection(receiver, record(args, ops)));
            case "difference" ->
                new JsNativeFunction("difference", (_, args) -> difference(receiver, record(args, ops)));
            case "symmetricDifference" -> new JsNativeFunction("symmetricDifference",
                    (_, args) -> symmetricDifference(receiver, record(args, ops)));
            case "isSubsetOf" ->
                new JsNativeFunction("isSubsetOf", (_, args) -> JsBoolean.of(isSubsetOf(receiver, record(args, ops))));
            case "isSupersetOf" -> new JsNativeFunction("isSupersetOf",
                    (_, args) -> JsBoolean.of(isSupersetOf(receiver, record(args, ops))));
            case "isDisjointFrom" -> new JsNativeFunction("isDisjointFrom",
                    (_, args) -> JsBoolean.of(isDisjointFrom(receiver, record(args, ops))));
            default -> null;
        };
    }

    // The spec's GetSetRecord: any object exposing a numeric `size` plus callable `has` and `keys`
    // is a valid argument, not just a real Set.
    private static SetRecord record(List<JsValue> args, InterpreterOps ops) {
        final var other = arg(args, 0);
        if (!InterpreterUtils.isObjectLike(other) || ops == null) {
            throw new TypeErrorException("Set method argument is not an object");
        }
        final var rawSize = ops.getMember(other, new JsString("size"));
        final var numSize = JsCoercion.toNumber(rawSize, ops);
        if (Double.isNaN(numSize)) {
            throw new TypeErrorException("Set method argument has no numeric size");
        }
        final var intSize = numSize < 0 ? -Math.floor(-numSize) : Math.floor(numSize);
        if (intSize < 0) {
            throw new RangeErrorException("Set method argument has a negative size");
        }
        final var has = ops.getMember(other, new JsString("has"));
        if (!InterpreterUtils.isCallable(has)) {
            throw new TypeErrorException("Set method argument has no callable has");
        }
        final var keys = ops.getMember(other, new JsString("keys"));
        if (!InterpreterUtils.isCallable(keys)) {
            throw new TypeErrorException("Set method argument has no callable keys");
        }
        return new SetRecord(other, intSize, has, keys, ops);
    }

    private record SetRecord(JsValue target, double size, JsValue has, JsValue keys, InterpreterOps ops) {
        boolean contains(JsValue value) {
            return JsCoercion.toBoolean(ops.call(has, target, List.of(value)));
        }

        KeysIterator openKeys() {
            final var iterator = ops.call(keys, target, List.of());
            final var next = ops.getMember(iterator, new JsString("next"));
            if (!InterpreterUtils.isCallable(next)) {
                throw new TypeErrorException("Set method argument keys iterator has no next");
            }
            return new KeysIterator(iterator, next, ops);
        }
    }

    private record KeysIterator(JsValue iterator, JsValue next, InterpreterOps ops) {
        JsValue step() {
            final var result = ops.call(next, iterator, List.of());
            if (JsCoercion.toBoolean(ops.getMember(result, new JsString("done")))) {
                return null;
            }
            return canonicalize(ops.getMember(result, new JsString("value")));
        }

        void close() {
            final var returnFn = ops.getMember(iterator, new JsString("return"));
            if (InterpreterUtils.isCallable(returnFn)) {
                ops.call(returnFn, iterator, List.of());
            }
        }
    }

    private static JsValue canonicalize(JsValue value) {
        return value instanceof JsNumber number && number.getValue() == 0 ? new JsNumber(0) : value;
    }

    private static JsSet copyOf(JsSet source) {
        final var result = new JsSet();
        for (final var value : source.values()) {
            result.add(value);
        }
        return result;
    }

    private static JsSet union(JsSet receiver, SetRecord other) {
        final var result = copyOf(receiver);
        final var keys = other.openKeys();
        for (var value = keys.step(); value != null; value = keys.step()) {
            result.add(value);
        }
        return result;
    }

    private static JsSet intersection(JsSet receiver, SetRecord other) {
        final var result = new JsSet();
        if (receiver.size() <= other.size()) {
            final var cursor = receiver.cursor();
            for (var value = cursor.next(); value != null; value = cursor.next()) {
                if (other.contains(value)) {
                    result.add(value);
                }
            }
            return result;
        }
        final var keys = other.openKeys();
        for (var value = keys.step(); value != null; value = keys.step()) {
            if (receiver.has(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static JsSet difference(JsSet receiver, SetRecord other) {
        final var result = copyOf(receiver);
        if (receiver.size() <= other.size()) {
            for (final var value : new ArrayList<>(receiver.values())) {
                if (other.contains(value)) {
                    result.delete(value);
                }
            }
            return result;
        }
        final var keys = other.openKeys();
        for (var value = keys.step(); value != null; value = keys.step()) {
            result.delete(value);
        }
        return result;
    }

    private static JsSet symmetricDifference(JsSet receiver, SetRecord other) {
        final var result = copyOf(receiver);
        final var keys = other.openKeys();
        for (var value = keys.step(); value != null; value = keys.step()) {
            if (receiver.has(value)) {
                result.delete(value);
            } else {
                result.add(value);
            }
        }
        return result;
    }

    // The receiver's [[SetData]] is walked live: the argument's `has` may delete an entry this loop
    // has not reached yet, and the spec re-reads the list length after every call.
    private static boolean isSubsetOf(JsSet receiver, SetRecord other) {
        if (receiver.size() > other.size()) {
            return false;
        }
        final var cursor = receiver.cursor();
        for (var value = cursor.next(); value != null; value = cursor.next()) {
            if (!other.contains(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupersetOf(JsSet receiver, SetRecord other) {
        if (receiver.size() < other.size()) {
            return false;
        }
        final var keys = other.openKeys();
        for (var value = keys.step(); value != null; value = keys.step()) {
            if (!receiver.has(value)) {
                keys.close();
                return false;
            }
        }
        return true;
    }

    private static boolean isDisjointFrom(JsSet receiver, SetRecord other) {
        if (receiver.size() <= other.size()) {
            final var cursor = receiver.cursor();
            for (var value = cursor.next(); value != null; value = cursor.next()) {
                if (other.contains(value)) {
                    return false;
                }
            }
            return true;
        }
        final var keys = other.openKeys();
        for (var value = keys.step(); value != null; value = keys.step()) {
            if (receiver.has(value)) {
                keys.close();
                return false;
            }
        }
        return true;
    }

    public static void add(JsSet set, JsValue value) {
        if (set.isWeak() && !isObjectKey(value)) {
            throw new TypeErrorException("Invalid value used in weak set");
        }
        set.add(value);
    }

    // Both iterators and forEach walk a live [[SetData]] cursor, so a member added, deleted or
    // re-added by the consumer is observed exactly as the spec prescribes.
    public static JsObject valuesIterator(JsSet set) {
        final var cursor = set.cursor();
        return JsIterators.lazy(_ -> cursor.next());
    }

    private static JsObject entriesIterator(JsSet set) {
        final var cursor = set.cursor();
        return JsIterators.lazy(_ -> {
            final var value = cursor.next();
            return value == null ? null : new JsArray(new ArrayList<>(List.of(value, value)));
        });
    }

    private static JsValue forEach(JsSet set, List<JsValue> args, Invoker invoker) {
        final var callback = arg(args, 0);
        if (!InterpreterUtils.isCallable(callback)) {
            throw new TypeErrorException("Set.prototype.forEach callbackfn is not a function");
        }
        final var thisArg = arg(args, 1);
        final var cursor = set.cursor();
        for (var value = cursor.next(); value != null; value = cursor.next()) {
            invoker.call(callback, thisArg, List.of(value, value, set));
        }
        return JsUndefined.getInstance();
    }

    private static boolean isObjectKey(JsValue value) {
        return switch (value) {
            case JsNumber ignored -> false;
            case JsString ignored -> false;
            case JsBoolean ignored -> false;
            case JsBigInt ignored -> false;
            case JsNull ignored -> false;
            case JsUndefined ignored -> false;
            default -> true;
        };
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }
}
