package org.techhouse.simplejs.values;

public final class JsSymbol extends JsValue {
    public static final JsSymbol DISPOSE = new JsSymbol("Symbol.dispose");
    public static final JsSymbol ASYNC_DISPOSE = new JsSymbol("Symbol.asyncDispose");

    private final String description;

    public JsSymbol(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
