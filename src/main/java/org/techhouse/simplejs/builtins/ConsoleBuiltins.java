package org.techhouse.simplejs.builtins;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsArray;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsNull;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class ConsoleBuiltins {
    private static final int MAX_LINE_CHARS = 1 << 16;
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
        final var line = new StringBuilder();
        for (final var arg : args) {
            if (!line.isEmpty()) {
                line.append(' ');
            }
            appendBounded(line, arg, Collections.newSetFromMap(new IdentityHashMap<>()));
        }
        consoleSink.accept(line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line.toString());
        return JsUndefined.getInstance();
    }

    private static void appendBounded(StringBuilder line, JsValue value, Set<JsArray> enclosing) {
        if (line.length() > MAX_LINE_CHARS) {
            return;
        }
        if (!(value instanceof JsArray array)) {
            line.append(JsCoercion.toStr(value));
            return;
        }
        if (enclosing.size() >= NativeDepth.MAX || !enclosing.add(array)) {
            return;
        }
        appendElements(line, array, enclosing);
        enclosing.remove(array);
    }

    private static void appendElements(StringBuilder line, JsArray array, Set<JsArray> enclosing) {
        final var elements = array.getElements();
        for (var i = 0; i < elements.size() && line.length() <= MAX_LINE_CHARS; i++) {
            if (i > 0) {
                line.append(',');
            }
            final var element = elements.get(i);
            if (!(element instanceof JsNull) && !(element instanceof JsUndefined)) {
                appendBounded(line, element, enclosing);
            }
        }
    }
}
