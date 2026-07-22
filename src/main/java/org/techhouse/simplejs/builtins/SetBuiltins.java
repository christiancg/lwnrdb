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
            default -> null;
        };
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
