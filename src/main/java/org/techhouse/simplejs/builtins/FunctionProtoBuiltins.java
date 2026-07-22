package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class FunctionProtoBuiltins {
    private FunctionProtoBuiltins() {
    }

    public static JsNativeFunction getMethod(JsValue target, String name, Invoker invoker) {
        return switch (name) {
            case "call" -> new JsNativeFunction("call", (_, args) -> call(target, args, invoker));
            case "apply" -> new JsNativeFunction("apply", (_, args) -> apply(target, args, invoker));
            case "bind" -> new JsNativeFunction("bound " + nameOf(target), (_, args) -> bind(target, args, invoker));
            default -> null;
        };
    }

    private static JsValue call(JsValue target, List<JsValue> args, Invoker invoker) {
        final var thisArg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var rest = args.isEmpty() ? List.<JsValue>of() : args.subList(1, args.size());
        return invoker.call(target, thisArg, new ArrayList<>(rest));
    }

    private static JsValue apply(JsValue target, List<JsValue> args, Invoker invoker) {
        final var thisArg = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var callArgs = new ArrayList<JsValue>();
        if (args.size() > 1 && args.get(1) instanceof JsArray array) {
            callArgs.addAll(array.getElements());
        }
        return invoker.call(target, thisArg, callArgs);
    }

    private static JsValue bind(JsValue target, List<JsValue> args, Invoker invoker) {
        final var boundThis = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        final var boundArgs = new ArrayList<>(args.isEmpty() ? List.of() : args.subList(1, args.size()));
        return new JsNativeFunction("bound " + nameOf(target), (_, callArgs) -> {
            final var combined = new ArrayList<>(boundArgs);
            combined.addAll(callArgs);
            return invoker.call(target, boundThis, combined);
        });
    }

    private static String nameOf(JsValue target) {
        if (target instanceof JsFunction fn) {
            return fn.getName() == null ? "" : fn.getName();
        }
        if (target instanceof JsNativeFunction nf) {
            return nf.getName() == null ? "" : nf.getName();
        }
        return "";
    }
}
