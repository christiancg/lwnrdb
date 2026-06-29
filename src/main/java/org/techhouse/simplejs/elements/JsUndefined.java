package org.techhouse.simplejs.elements;

public class JsUndefined extends JsBaseElement {
    private static final JsUndefined instance = new JsUndefined();
    private JsUndefined() {
    }
    public static JsUndefined getValue() {
        return instance;
    }
}
