package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsMap;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class MapBuiltins {
    private MapBuiltins() {
    }

    public static JsNativeFunction create(IterableToList iterableToList, boolean weak) {
        return new JsNativeFunction(weak ? "WeakMap" : "Map", (_, args) -> construct(args, iterableToList, weak));
    }

    private static JsValue construct(List<JsValue> args, IterableToList iterableToList, boolean weak) {
        final var map = new JsMap(weak);
        if (!args.isEmpty() && !(args.getFirst() instanceof JsUndefined) && !(args.getFirst() instanceof JsNull)) {
            for (final var entry : iterableToList.drain(args.getFirst())) {
                final var pair = iterableToList.drain(entry);
                set(map, pair.isEmpty() ? JsUndefined.getInstance() : pair.getFirst(),
                        pair.size() < 2 ? JsUndefined.getInstance() : pair.get(1));
            }
        }
        return map;
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
            case "forEach" -> new JsNativeFunction("forEach", (_, args) -> forEach(receiver, args, invoker));
            case "keys" -> new JsNativeFunction("keys", (_, _) -> keysIterator(receiver));
            case "values" -> new JsNativeFunction("values", (_, _) -> valuesIterator(receiver));
            case "entries" -> new JsNativeFunction("entries", (_, _) -> entriesIterator(receiver));
            default -> null;
        };
    }

    public static void set(JsMap map, JsValue key, JsValue value) {
        if (map.isWeak() && !isObjectKey(key)) {
            throw new TypeErrorException("Invalid value used as weak map key");
        }
        map.set(key, value);
    }

    public static JsObject entriesIterator(JsMap map) {
        final var snapshot = new ArrayList<JsValue>();
        for (final var entry : map.entries()) {
            snapshot.add(new JsArray(new ArrayList<>(List.of(entry.key(), entry.value()))));
        }
        return JsIterators.of(snapshot.iterator());
    }

    private static JsObject keysIterator(JsMap map) {
        final var snapshot = new ArrayList<JsValue>();
        for (final var entry : map.entries()) {
            snapshot.add(entry.key());
        }
        return JsIterators.of(snapshot.iterator());
    }

    private static JsObject valuesIterator(JsMap map) {
        final var snapshot = new ArrayList<JsValue>();
        for (final var entry : map.entries()) {
            snapshot.add(entry.value());
        }
        return JsIterators.of(snapshot.iterator());
    }

    private static JsValue forEach(JsMap map, List<JsValue> args, Invoker invoker) {
        final var callback = arg(args, 0);
        final var thisArg = arg(args, 1);
        for (final var entry : new ArrayList<>(map.entries())) {
            invoker.call(callback, thisArg, List.of(entry.value(), entry.key(), map));
        }
        return JsUndefined.getInstance();
    }

    private static boolean isObjectKey(JsValue value) {
        return switch (value) {
            case JsNumber ignored -> false;
            case org.techhouse.simplejs.values.JsString ignored -> false;
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
