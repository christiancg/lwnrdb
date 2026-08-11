package org.techhouse.simplejs.values;

public final class JsSymbol extends JsValue {
    public static final JsSymbol DISPOSE = new JsSymbol("Symbol.dispose");
    public static final JsSymbol ASYNC_DISPOSE = new JsSymbol("Symbol.asyncDispose");
    public static final JsSymbol ITERATOR = new JsSymbol("Symbol.iterator");
    public static final JsSymbol ASYNC_ITERATOR = new JsSymbol("Symbol.asyncIterator");
    public static final JsSymbol TO_PRIMITIVE = new JsSymbol("Symbol.toPrimitive");
    public static final JsSymbol HAS_INSTANCE = new JsSymbol("Symbol.hasInstance");
    public static final JsSymbol TO_STRING_TAG = new JsSymbol("Symbol.toStringTag");
    public static final JsSymbol MATCH = new JsSymbol("Symbol.match");
    public static final JsSymbol REPLACE = new JsSymbol("Symbol.replace");
    public static final JsSymbol SEARCH = new JsSymbol("Symbol.search");
    public static final JsSymbol SPLIT = new JsSymbol("Symbol.split");
    public static final JsSymbol MATCH_ALL = new JsSymbol("Symbol.matchAll");
    public static final JsSymbol IS_CONCAT_SPREADABLE = new JsSymbol("Symbol.isConcatSpreadable");
    public static final JsSymbol UNSCOPABLES = new JsSymbol("Symbol.unscopables");

    private final String description;

    public JsSymbol(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
