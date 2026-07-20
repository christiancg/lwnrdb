package org.techhouse.simplejs.values;

public final class JsUndefined extends JsValue {
    private static final JsUndefined instance = new JsUndefined();

    private JsUndefined() {
    }

    public static JsUndefined getInstance() {
        return instance;
    }
}
