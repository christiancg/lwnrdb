package org.techhouse.simplejs.builtins;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.techhouse.simplejs.exceptions.TypeErrorException;
import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsObject;
import org.techhouse.simplejs.values.JsString;
import org.techhouse.simplejs.values.JsSymbol;
import org.techhouse.simplejs.values.JsUndefined;
import org.techhouse.simplejs.values.JsValue;

public final class SymbolBuiltins {
    public static final List<String> NAMES = List.of("toString", "valueOf");
    public static final List<String> PROTO_ACCESSORS = List.of("description");

    private static final JsObject.PropertyFlags FROZEN = new JsObject.PropertyFlags(false, false, false);

    private SymbolBuiltins() {
    }

    public static JsNativeFunction create(InterpreterOps ops) {
        // The Symbol.for registry is per-realm (one instance per Symbol namespace, i.e. per
        // Interpreter run) rather than JVM-global: a static map would grow unbounded across
        // script runs and would leak symbol identities between different users' scripts.
        final Map<String, JsSymbol> registry = new ConcurrentHashMap<>();
        final var symbol = new JsNativeFunction("Symbol", (thisArg, args) -> {
            // The Symbol constructor is not intended to be subclassed: any invocation via `new`
            // must throw before a symbol is ever created. A direct `new Symbol()` carries a
            // new.target; a subclass's `super()` call instead arrives with the instance under
            // construction as `thisArg` (the applyNativeSuper convention every other constructible
            // builtin here relies on, since new.target is deliberately not threaded through that
            // path - see ClassEvaluator.applyNativeSuper).
            if (JsNativeFunction.currentNewTarget() != null || !(thisArg instanceof JsUndefined)) {
                throw new TypeErrorException("Symbol is not a constructor");
            }
            return new JsSymbol(args.isEmpty() || args.getFirst() instanceof JsUndefined
                    ? null
                    : JsCoercion.toStr(args.getFirst(), ops));
        });
        wellKnown(symbol, "dispose", JsSymbol.DISPOSE);
        wellKnown(symbol, "asyncDispose", JsSymbol.ASYNC_DISPOSE);
        wellKnown(symbol, "iterator", JsSymbol.ITERATOR);
        wellKnown(symbol, "asyncIterator", JsSymbol.ASYNC_ITERATOR);
        wellKnown(symbol, "toPrimitive", JsSymbol.TO_PRIMITIVE);
        wellKnown(symbol, "hasInstance", JsSymbol.HAS_INSTANCE);
        wellKnown(symbol, "toStringTag", JsSymbol.TO_STRING_TAG);
        wellKnown(symbol, "match", JsSymbol.MATCH);
        wellKnown(symbol, "replace", JsSymbol.REPLACE);
        wellKnown(symbol, "search", JsSymbol.SEARCH);
        wellKnown(symbol, "split", JsSymbol.SPLIT);
        wellKnown(symbol, "matchAll", JsSymbol.MATCH_ALL);
        wellKnown(symbol, "isConcatSpreadable", JsSymbol.IS_CONCAT_SPREADABLE);
        wellKnown(symbol, "unscopables", JsSymbol.UNSCOPABLES);
        wellKnown(symbol, "species", JsSymbol.SPECIES);
        symbol.setProperty("for",
                new JsNativeFunction("for", (_, args) -> registry.computeIfAbsent(key(args, ops), registryKey -> {
                    final var registered = new JsSymbol(registryKey);
                    registered.markRegistered();
                    return registered;
                })));
        symbol.setProperty("keyFor", new JsNativeFunction("keyFor", (_, args) -> keyFor(registry, args)));
        return symbol;
    }

    // A well-known symbol is { [[Writable]]: false, [[Enumerable]]: false, [[Configurable]]: false },
    // unlike every other own property of a builtin constructor.
    private static void wellKnown(JsNativeFunction symbol, String name, JsSymbol value) {
        final var table = symbol.ownProperties();
        table.defineValue(name, value);
        table.setFlags(name, FROZEN);
    }

    public static JsNativeFunction getMethod(JsSymbol receiver, String name) {
        return switch (name) {
            case "toString" -> new JsNativeFunction("toString", (_, _) -> new JsString(describe(receiver)));
            case "valueOf" -> new JsNativeFunction("valueOf", (_, _) -> receiver);
            default -> null;
        };
    }

    public static JsValue getProperty(JsSymbol receiver, String name) {
        if ("description".equals(name)) {
            return descriptionOf(receiver);
        }
        return null;
    }

    public static JsValue descriptionOf(JsSymbol receiver) {
        return receiver.getDescription() == null ? JsUndefined.getInstance() : new JsString(receiver.getDescription());
    }

    public static String describe(JsSymbol symbol) {
        return "Symbol(" + (symbol.getDescription() == null ? "" : symbol.getDescription()) + ")";
    }

    private static String key(List<JsValue> args, InterpreterOps ops) {
        return args.isEmpty() ? "undefined" : JsCoercion.toStr(args.getFirst(), ops);
    }

    private static JsValue keyFor(Map<String, JsSymbol> registry, List<JsValue> args) {
        if (args.isEmpty() || !(args.getFirst() instanceof JsSymbol symbol)) {
            throw new TypeErrorException("Symbol.keyFor requires a symbol argument");
        }
        for (final var entry : registry.entrySet()) {
            if (entry.getValue() == symbol) {
                return new JsString(entry.getKey());
            }
        }
        return JsUndefined.getInstance();
    }
}
