package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsValue;

public final class GlobalScope {
    private GlobalScope() {
    }

    public static void install(Environment global) {
        ErrorBuiltins.install(global);
        define(global, "Object", ObjectBuiltins.create());
        define(global, "Array", ArrayBuiltins.create());
        define(global, "String", StringBuiltins.create());
        define(global, "Number", NumberBuiltins.create());
        define(global, "Boolean", booleanFunction());
        define(global, "Math", MathBuiltins.create());
        define(global, "JSON", JsonBuiltins.create());
        define(global, "console", ConsoleBuiltins.create());
        define(global, "parseInt", NumberBuiltins.parseIntFunction());
        define(global, "parseFloat", NumberBuiltins.parseFloatFunction());
        define(global, "isNaN", NumberBuiltins.isNaNFunction());
        define(global, "isFinite", NumberBuiltins.isFiniteFunction());
    }

    private static JsNativeFunction booleanFunction() {
        return new JsNativeFunction("Boolean", (_, args) -> JsBoolean
                .of(!args.isEmpty() && org.techhouse.simplejs.internal.JsCoercion.toBoolean(args.getFirst())));
    }

    private static void define(Environment global, String name, JsValue value) {
        global.declareVar(name);
        global.assign(name, value);
    }
}
