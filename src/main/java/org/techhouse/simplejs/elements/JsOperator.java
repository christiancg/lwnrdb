package org.techhouse.simplejs.elements;

public class JsOperator extends JsBaseElement {
    private final String value;
    public JsOperator(String value) {
        this.value = value;
    }
    public String getValue() {
        return value;
    }
}
