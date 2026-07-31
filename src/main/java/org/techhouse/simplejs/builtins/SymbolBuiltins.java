package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class SymbolBuiltins {
    private SymbolBuiltins() {
    }

    public static JsNativeFunction create() {
        // The Symbol.for registry is per-realm (one instance per Symbol namespace, i.e. per
        // Interpreter run) rather than JVM-global: a static map would grow unbounded across
        // script runs and would leak symbol identities between different users' scripts.
        final Map<String, JsSymbol> registry = new ConcurrentHashMap<>();
        final var symbol = new JsNativeFunction("Symbol",
                (_, args) -> new JsSymbol(args.isEmpty() ? null : JsCoercion.toStr(args.getFirst())));
        symbol.setProperty("dispose", JsSymbol.DISPOSE);
        symbol.setProperty("asyncDispose", JsSymbol.ASYNC_DISPOSE);
        symbol.setProperty("iterator", JsSymbol.ITERATOR);
        symbol.setProperty("asyncIterator", JsSymbol.ASYNC_ITERATOR);
        symbol.setProperty("toPrimitive", JsSymbol.TO_PRIMITIVE);
        symbol.setProperty("for",
                new JsNativeFunction("for", (_, args) -> registry.computeIfAbsent(key(args), JsSymbol::new)));
        symbol.setProperty("keyFor", new JsNativeFunction("keyFor", (_, args) -> keyFor(registry, args)));
        return symbol;
    }

    private static String key(List<JsValue> args) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst());
    }

    private static JsValue keyFor(Map<String, JsSymbol> registry, List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsSymbol symbol)) {
            return JsUndefined.getInstance();
        }
        for (final var entry : registry.entrySet()) {
            if (entry.getValue() == symbol) {
                return new JsString(entry.getKey());
            }
        }
        return JsUndefined.getInstance();
    }
}
