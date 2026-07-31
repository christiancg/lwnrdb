package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
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
    private SetBuiltins() {
    }

    public static JsNativeFunction create(IterableToList iterableToList, boolean weak) {
        return new JsNativeFunction(weak ? "WeakSet" : "Set", (_, args) -> construct(args, iterableToList, weak));
    }

    private static JsValue construct(List<JsValue> args, IterableToList iterableToList, boolean weak) {
        final var set = new JsSet(weak);
        if (!args.isEmpty() && !(args.getFirst() instanceof JsUndefined) && !(args.getFirst() instanceof JsNull)) {
            for (final var value : iterableToList.drain(args.getFirst())) {
                add(set, value);
            }
        }
        return set;
    }

    public static JsValue getMethod(JsSet receiver, String name, Invoker invoker) {
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
            case "union" -> new JsNativeFunction("union", (_, args) -> union(receiver, other(args)));
            case "intersection" ->
                new JsNativeFunction("intersection", (_, args) -> intersection(receiver, other(args)));
            case "difference" -> new JsNativeFunction("difference", (_, args) -> difference(receiver, other(args)));
            case "symmetricDifference" ->
                new JsNativeFunction("symmetricDifference", (_, args) -> symmetricDifference(receiver, other(args)));
            case "isSubsetOf" ->
                new JsNativeFunction("isSubsetOf", (_, args) -> JsBoolean.of(isSubsetOf(receiver, other(args))));
            case "isSupersetOf" ->
                new JsNativeFunction("isSupersetOf", (_, args) -> JsBoolean.of(isSupersetOf(receiver, other(args))));
            case "isDisjointFrom" -> new JsNativeFunction("isDisjointFrom",
                    (_, args) -> JsBoolean.of(isDisjointFrom(receiver, other(args))));
            default -> null;
        };
    }

    private static JsSet other(List<JsValue> args) {
        if (arg(args, 0) instanceof JsSet set) {
            return set;
        }
        throw new TypeErrorException("Set method argument is not a Set");
    }

    private static JsSet union(JsSet receiver, JsSet other) {
        final var result = new JsSet();
        for (final var value : receiver.values()) {
            result.add(value);
        }
        for (final var value : other.values()) {
            result.add(value);
        }
        return result;
    }

    private static JsSet intersection(JsSet receiver, JsSet other) {
        final var result = new JsSet();
        for (final var value : receiver.values()) {
            if (other.has(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static JsSet difference(JsSet receiver, JsSet other) {
        final var result = new JsSet();
        for (final var value : receiver.values()) {
            if (!other.has(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static JsSet symmetricDifference(JsSet receiver, JsSet other) {
        final var result = new JsSet();
        for (final var value : receiver.values()) {
            if (!other.has(value)) {
                result.add(value);
            }
        }
        for (final var value : other.values()) {
            if (!receiver.has(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static boolean isSubsetOf(JsSet receiver, JsSet other) {
        for (final var value : receiver.values()) {
            if (!other.has(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSupersetOf(JsSet receiver, JsSet other) {
        for (final var value : other.values()) {
            if (!receiver.has(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDisjointFrom(JsSet receiver, JsSet other) {
        for (final var value : receiver.values()) {
            if (other.has(value)) {
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

    public static JsObject valuesIterator(JsSet set) {
        return JsIterators.of(new ArrayList<>(set.values()).iterator());
    }

    private static JsObject entriesIterator(JsSet set) {
        final var snapshot = new ArrayList<JsValue>();
        for (final var value : set.values()) {
            snapshot.add(new JsArray(new ArrayList<>(List.of(value, value))));
        }
        return JsIterators.of(snapshot.iterator());
    }

    private static JsValue forEach(JsSet set, List<JsValue> args, Invoker invoker) {
        final var callback = arg(args, 0);
        final var thisArg = arg(args, 1);
        for (final var value : new ArrayList<>(set.values())) {
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
