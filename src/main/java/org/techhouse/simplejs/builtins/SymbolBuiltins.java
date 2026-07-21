package org.techhouse.simplejs.builtins;

import org.techhouse.simplejs.internal.JsCoercion;
import org.techhouse.simplejs.values.JsNativeFunction;
import org.techhouse.simplejs.values.JsSymbol;

public final class SymbolBuiltins {
    private SymbolBuiltins() {
    }

    public static JsNativeFunction create() {
        final var symbol = new JsNativeFunction("Symbol",
                (_, args) -> new JsSymbol(args.isEmpty() ? null : JsCoercion.toStr(args.getFirst())));
        symbol.setProperty("dispose", JsSymbol.DISPOSE);
        symbol.setProperty("asyncDispose", JsSymbol.ASYNC_DISPOSE);
        return symbol;
    }
}
