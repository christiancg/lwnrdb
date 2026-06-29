package org.techhouse.simplejs.elements;

public class JsKeyword extends JsBaseElement {
    private final String value;
    public JsKeyword(String value) {
        this.value = value;
    }
    public String getValue() {
        return value;
    }
}
