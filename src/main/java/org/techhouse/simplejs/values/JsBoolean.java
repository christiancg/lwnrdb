package org.techhouse.simplejs.values;

public final class JsBoolean extends JsValue {
    public static final JsBoolean TRUE = new JsBoolean(true);
    public static final JsBoolean FALSE = new JsBoolean(false);

    private final boolean value;

    private JsBoolean(boolean value) {
        this.value = value;
    }

    public static JsBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    public boolean getValue() {
        return value;
    }
}
