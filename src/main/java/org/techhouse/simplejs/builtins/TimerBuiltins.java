package org.techhouse.simplejs.builtins;

import java.util.List;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.Environment;
import org.techhouse.simplejs.internal.EventLoop;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsFunction;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNumber;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class TimerBuiltins {
    private TimerBuiltins() {
    }

    public static void install(Environment global, EventLoop eventLoop, Invoker invoker) {
        define(global, "setTimeout", schedule("setTimeout", eventLoop, invoker, false));
        define(global, "setInterval", schedule("setInterval", eventLoop, invoker, true));
        define(global, "clearTimeout", clear("clearTimeout", eventLoop));
        define(global, "clearInterval", clear("clearInterval", eventLoop));
    }

    private static JsNativeFunction schedule(String name, EventLoop eventLoop, Invoker invoker, boolean repeat) {
        return new JsNativeFunction(name, (_, args) -> {
            final var callback = args.isEmpty() ? JsUndefined.getInstance() : args.getFirst();
            if (!(callback instanceof JsFunction) && !(callback instanceof JsNativeFunction)) {
                throw new TypeErrorException(name + " callback is not a function");
            }
            final var delay = args.size() > 1 ? (long) JsCoercion.toNumber(args.get(1)) : 0L;
            final var extraArgs = args.size() > 2 ? List.copyOf(args.subList(2, args.size())) : List.<JsValue>of();
            final var id = eventLoop.setTimer(() -> invoker.call(callback, JsUndefined.getInstance(), extraArgs), delay,
                    repeat);
            return new JsNumber(id);
        });
    }

    private static JsNativeFunction clear(String name, EventLoop eventLoop) {
        return new JsNativeFunction(name, (_, args) -> {
            if (!args.isEmpty()) {
                eventLoop.clearTimer((long) JsCoercion.toNumber(args.getFirst()));
            }
            return JsUndefined.getInstance();
        });
    }

    private static void define(Environment global, String name, JsValue value) {
        global.declareBuiltin(name, value);
    }
}
