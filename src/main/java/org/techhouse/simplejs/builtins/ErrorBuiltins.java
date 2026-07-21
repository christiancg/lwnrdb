package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ErrorBuiltins {
    private static final List<String> NAMES = List.of("Error", "TypeError", "RangeError", "SyntaxError");

    private ErrorBuiltins() {
    }

    public static JsObject makeError(String name, String message) {
        final var error = new JsObject();
        error.set("name", new JsString(name));
        error.set("message", new JsString(message));
        return error;
    }

    public static JsObject makeSuppressedError(JsValue error, JsValue suppressed, String message) {
        final var result = makeError("SuppressedError", message);
        result.set("error", error);
        result.set("suppressed", suppressed);
        return result;
    }

    public static void install(Environment global) {
        for (final var name : NAMES) {
            global.declareVar(name);
            global.assign(name, new JsNativeFunction(name, (_, args) -> makeError(name, message(args))));
        }
        global.declareVar("SuppressedError");
        global.assign("SuppressedError",
                new JsNativeFunction("SuppressedError", (_, args) -> makeSuppressedError(arg(args, 0), arg(args, 1),
                        args.size() > 2 ? message(List.of(args.get(2))) : "")));
    }

    private static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    private static String message(List<JsValue> args) {
        return args.isEmpty() ? "" : JsCoercion.toStr(args.getFirst());
    }
}
