package org.techhouse.simplejs.values;

public final class JsSymbol extends JsValue {
    public static final JsSymbol DISPOSE = new JsSymbol("Symbol.dispose");
    public static final JsSymbol ASYNC_DISPOSE = new JsSymbol("Symbol.asyncDispose");
    public static final JsSymbol ITERATOR = new JsSymbol("Symbol.iterator");
    public static final JsSymbol ASYNC_ITERATOR = new JsSymbol("Symbol.asyncIterator");
    public static final JsSymbol TO_PRIMITIVE = new JsSymbol("Symbol.toPrimitive");

    private final String description;

    public JsSymbol(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
