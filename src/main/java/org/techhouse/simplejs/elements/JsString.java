package org.techhouse.simplejs.elements;

public class JsString extends JsBaseElement {
    private final String value;
    public JsString(String value) {
        this.value = value;
    }
    public String getValue() {
        return value;
    }
}
