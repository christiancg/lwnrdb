package org.techhouse.simplejs.elements;

public class JsNull extends JsBaseElement {
    private static final JsNull instance = new JsNull();
    private JsNull() {
    }
    public static JsNull getValue() {
        return instance;
    }
}
