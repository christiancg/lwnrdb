package org.techhouse.simplejs.values;

public final class JsUndefined extends JsValue {
    private static final JsUndefined instance = new JsUndefined();
    private static final JsUndefined hole = new JsUndefined();

    private JsUndefined() {
    }

    public static JsUndefined getInstance() {
        return instance;
    }

    public static JsUndefined getHole() {
        return hole;
    }
}
