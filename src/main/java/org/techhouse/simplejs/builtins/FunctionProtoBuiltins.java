package org.techhouse.simplejs.builtins;

import java.util.ArrayList;
import java.util.List;
import org.techhouse.simplejs.nodes.Identifier;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class FunctionProtoBuiltins {
    public static final List<String> NAMES = List.of("call", "apply", "bind", "toString");

    private FunctionProtoBuiltins() {
    }

    public static JsNativeFunction getMethod(JsValue target, String name, Invoker invoker) {
        return switch (name) {
            case "call" -> new JsNativeFunction("call", (_, args) -> call(target, args, invoker));
            case "apply" -> new JsNativeFunction("apply", (_, args) -> apply(target, args, invoker));
            case "bind" -> new JsNativeFunction("bound " + nameOf(target), (_, args) -> bind(target, args, invoker));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(sourceText(target)));
            default -> null;
        };
    }

    // No source text is retained for closures, so both natives and user functions report the
    // native-code shape rather than the original body.
    private static String sourceText(JsValue target) {
        return "function " + nameOf(target) + "() { [native code] }";
    }

    public static JsValue metadata(JsValue target, String key) {
        if ("name".equals(key)) {
            return new JsString(nameOf(target));
        }
        if ("length".equals(key)) {
            return new JsNumber(declaredLength(target));
        }
        return null;
    }

    private static int declaredLength(JsValue target) {
        if (target instanceof JsNativeFunction nf && nf.hasExplicitLength()) {
            return nf.getExplicitLength();
        }
        if (!(target instanceof JsFunction fn)) {
            return 0;
        }
        var count = 0;
        for (final var param : fn.getParams()) {
            if (!(param instanceof Identifier)) {
                break;
            }
            count++;
        }
        return count;
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
        final var bound = new JsNativeFunction("bound " + nameOf(target), (_, callArgs) -> {
            final var combined = new ArrayList<>(boundArgs);
            combined.addAll(callArgs);
            return invoker.call(target, boundThis, combined);
        });
        bound.setBound(target, boundArgs);
        return bound;
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
