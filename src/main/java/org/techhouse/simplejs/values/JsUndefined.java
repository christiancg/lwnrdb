package org.techhouse.simplejs.values;

public final class JsUndefined extends JsValue {
    private static final JsUndefined instance = new JsUndefined();
    private static final JsUndefined hole = new JsUndefined();

    private JsUndefined() {
    }

    public static JsUndefined getInstance() {
        return instance;
    }

    // The array-elision marker. It is a real undefined, so every reader that does not care about
    // holes behaves exactly as before; hole-aware code compares identity against this instance.
    public static JsUndefined getHole() {
        return hole;
    }
}
