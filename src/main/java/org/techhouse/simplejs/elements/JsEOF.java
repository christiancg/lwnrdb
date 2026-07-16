package org.techhouse.simplejs.elements;

public final class JsEOF extends JsBaseElement {
    private static final JsEOF instance = new JsEOF();
    private JsEOF() {
    }
    public static JsEOF getInstance() {
        return instance;
    }
}
