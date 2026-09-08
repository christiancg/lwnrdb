package org.techhouse.simplejs.values;

public final class JsNull extends JsValue {
    private static final JsNull instance = new JsNull();

    private JsNull() {
    }

    public static JsNull getInstance() {
        return instance;
    }
}
