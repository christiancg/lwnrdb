package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ConsoleBuiltins {
    private static Consumer<String> sink = System.out::println;

    private ConsoleBuiltins() {
    }

    public static void setSink(Consumer<String> newSink) {
        sink = newSink;
    }

    public static JsObject create() {
        return create(sink);
    }

    public static JsObject create(Consumer<String> consoleSink) {
        final var console = new JsObject();
        Intrinsics.defineHidden(console, "log", new JsNativeFunction("log", (_, args) -> write(consoleSink, args)));
        Intrinsics.defineHidden(console, "error", new JsNativeFunction("error", (_, args) -> write(consoleSink, args)));
        Intrinsics.defineHidden(console, "warn", new JsNativeFunction("warn", (_, args) -> write(consoleSink, args)));
        Intrinsics.defineHidden(console, "info", new JsNativeFunction("info", (_, args) -> write(consoleSink, args)));
        return console;
    }

    private static JsValue write(Consumer<String> consoleSink, List<JsValue> args) {
        consoleSink.accept(args.stream().map(JsCoercion::toStr).collect(Collectors.joining(" ")));
        return JsUndefined.getInstance();
    }
}
