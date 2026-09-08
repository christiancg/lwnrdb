package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.internal.interpreter.InterpreterUtils;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class BuiltinArgs {
    public static JsValue arg(List<JsValue> args, int index) {
        return index < args.size() ? args.get(index) : JsUndefined.getInstance();
    }

    public static JsValue arg0(List<JsValue> args) {
        return args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
    }

    public static JsValue callback(List<JsValue> args) {
        final var fn = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
        if (!InterpreterUtils.isCallable(fn)) {
            throw new TypeErrorException(JsCoercion.toStr(fn) + " is not a function");
        }
        return fn;
    }

    public static String str(List<JsValue> args, int index, InterpreterOps ops) {
        return index < args.size() ? JsCoercion.toStr(args.get(index), ops) : "undefined";
    }

    private BuiltinArgs() {
    }
}
