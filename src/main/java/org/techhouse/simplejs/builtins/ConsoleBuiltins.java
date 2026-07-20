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
        final var console = new JsObject();
        console.set("log", new JsNativeFunction("log", (_, args) -> write(args)));
        console.set("error", new JsNativeFunction("error", (_, args) -> write(args)));
        console.set("warn", new JsNativeFunction("warn", (_, args) -> write(args)));
        console.set("info", new JsNativeFunction("info", (_, args) -> write(args)));
        return console;
    }

    private static JsValue write(List<JsValue> args) {
        sink.accept(args.stream().map(JsCoercion::toStr).collect(Collectors.joining(" ")));
        return JsUndefined.getInstance();
    }
}
