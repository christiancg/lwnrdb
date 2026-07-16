package org.techhouse.simplejs.elements;

public final class JsNull extends JsBaseElement {
    private static final JsNull instance = new JsNull();
    private JsNull() {
    }
    public static JsNull getInstance() {
        return instance;
    }
}
