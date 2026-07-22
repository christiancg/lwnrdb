package org.techhouse.simplejs.builtins;

import java.util.function.Consumer;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.values.JsBoolean;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsValue;

public final class GlobalScope {
    private GlobalScope() {
    }

    public static void install(Environment global, EventLoop eventLoop, Invoker invoker, Consumer<String> consoleSink) {
        final var globalObject = new JsObject();
        ErrorBuiltins.install(global);
        define(global, globalObject, "Object", ObjectBuiltins.create());
        define(global, globalObject, "Array", ArrayBuiltins.create());
        define(global, globalObject, "String", StringBuiltins.create());
        define(global, globalObject, "Number", NumberBuiltins.create());
        define(global, globalObject, "Boolean", booleanFunction());
        define(global, globalObject, "Math", MathBuiltins.create());
        define(global, globalObject, "JSON", JsonBuiltins.create());
        define(global, globalObject, "console",
                consoleSink == null ? ConsoleBuiltins.create() : ConsoleBuiltins.create(consoleSink));
        define(global, globalObject, "Promise", PromiseBuiltins.create(eventLoop, invoker));
        TimerBuiltins.install(global, eventLoop, invoker);
        define(global, globalObject, "RegExp", RegexBuiltins.create());
        define(global, globalObject, "Symbol", SymbolBuiltins.create());
        define(global, globalObject, "parseInt", NumberBuiltins.parseIntFunction());
        define(global, globalObject, "parseFloat", NumberBuiltins.parseFloatFunction());
        define(global, globalObject, "isNaN", NumberBuiltins.isNaNFunction());
        define(global, globalObject, "isFinite", NumberBuiltins.isFiniteFunction());
        define(global, globalObject, "globalThis", globalObject);
    }

    private static JsNativeFunction booleanFunction() {
        return new JsNativeFunction("Boolean", (_, args) -> JsBoolean
                .of(!args.isEmpty() && org.techhouse.simplejs.internal.JsCoercion.toBoolean(args.getFirst())));
    }

    private static void define(Environment global, JsObject globalObject, String name, JsValue value) {
        global.declareVar(name);
        global.assign(name, value);
        globalObject.set(name, value);
    }
}
