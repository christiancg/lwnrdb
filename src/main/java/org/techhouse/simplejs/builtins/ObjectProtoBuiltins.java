package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsValue;

public final class ObjectProtoBuiltins {
    private ObjectProtoBuiltins() {
    }

    public static JsNativeFunction getMethod(JsObject receiver, String name, InterpreterOps ops) {
        return switch (name) {
            case "hasOwnProperty" -> new JsNativeFunction("hasOwnProperty",
                    (_, args) -> JsBoolean.of(!args.isEmpty() && receiver.has(JsCoercion.toStr(args.getFirst()))));
            case "isPrototypeOf" ->
                new JsNativeFunction("isPrototypeOf", (_, args) -> JsBoolean.of(isPrototypeOf(receiver, args)));
            case "propertyIsEnumerable" ->
                new JsNativeFunction("propertyIsEnumerable", (_, args) -> JsBoolean.of(isEnumerable(receiver, args)));
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(objectToString(receiver, ops)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> receiver);
            default -> null;
        };
    }

    private static String objectToString(JsObject receiver, InterpreterOps ops) {
        if (ops != null) {
            final var tag = ops.getMember(receiver, JsSymbol.TO_STRING_TAG);
            if (tag instanceof JsString tagString) {
                return "[object " + tagString.getValue() + "]";
            }
        }
        return "[object Object]";
    }

    private static boolean isEnumerable(JsObject receiver, List<JsValue> args) {
        if (args.isEmpty()) {
            return false;
        }
        final var key = JsCoercion.toStr(args.getFirst());
        return receiver.has(key) && receiver.isEnumerable(key);
    }

    private static boolean isPrototypeOf(JsObject receiver, List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsObject candidate)) {
            return false;
        }
        for (var proto = candidate.getProto(); proto != null; proto = proto.getProto()) {
            if (proto == receiver) {
                return true;
            }
        }
        return false;
    }
}
