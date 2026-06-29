package org.techhouse.simplejs.elements;

public class JsEOF extends JsBaseElement {
    private static final JsEOF instance = new JsEOF();
    private JsEOF() {
    }
    public static JsEOF getInstance() {
        return instance;
    }
}
