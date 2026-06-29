package org.techhouse.simplejs.elements;

public class JsNumber extends JsBaseElement {
    private final Number value;
    public JsNumber(Number value) {
        this.value = value;
    }
    public Number getValue() {
        return value;
    }
}
