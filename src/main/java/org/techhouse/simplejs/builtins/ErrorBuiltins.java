package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
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

    public static void install(Environment global) {
        for (final var name : NAMES) {
            global.declareVar(name);
            global.assign(name, new JsNativeFunction(name, (_, args) -> makeError(name, message(args))));
        }
    }

    private static String message(List<JsValue> args) {
        return args.isEmpty() ? "" : JsCoercion.toStr(args.getFirst());
    }
}
