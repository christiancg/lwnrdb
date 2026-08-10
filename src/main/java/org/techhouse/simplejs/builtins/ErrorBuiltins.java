package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ErrorBuiltins {
    private static final List<String> NAMES = List.of("Error", "TypeError", "RangeError", "SyntaxError", "URIError");

    private ErrorBuiltins() {
    }

    public static JsObject makeError(String name, String message) {
        final var error = new JsObject();
        error.set("name", new JsString(name));
        error.set("message", new JsString(message));
        error.markErrorData();
        return error;
    }

    public static JsObject makeSuppressedError(JsValue error, JsValue suppressed, String message) {
        final var result = makeError("SuppressedError", message);
        result.set("error", error);
        result.set("suppressed", suppressed);
        return result;
    }

    public static JsObject makeAggregateError(List<JsValue> errors, String message) {
        final var result = makeError("AggregateError", message);
        final var array = new JsArray();
        for (final var error : errors) {
            array.push(error);
        }
        result.set("errors", array);
        return result;
    }

    public static void install(Environment global) {
        for (final var name : NAMES) {
            final var constructor = new JsNativeFunction(name, (_, args) -> makeError(name, message(args)));
            if ("Error".equals(name)) {
                constructor.setProperty("isError",
                        new JsNativeFunction("isError", (_, args) -> JsBoolean.of(isError(arg(args, 0)))));
            }
            global.declareBuiltin(name, constructor);
        }
        global.declareBuiltin("SuppressedError",
                new JsNativeFunction("SuppressedError", (_, args) -> makeSuppressedError(arg(args, 0), arg(args, 1),
                        args.size() > 2 ? message(List.of(args.get(2))) : "")));
        global.declareBuiltin("AggregateError", new JsNativeFunction("AggregateError", (_, args) -> {
            final var errors = arg(args, 0) instanceof JsArray array ? array.getElements() : List.<JsValue>of();
            return makeAggregateError(errors, args.size() > 1 ? message(List.of(args.get(1))) : "");
        }));
    }

    private static boolean isError(JsValue value) {
        return value instanceof JsObject object && object.isErrorData();
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    private static String message(List<JsValue> args) {
        return args.isEmpty() ? "" : JsCoercion.toStr(args.getFirst());
    }
}
