package org.techhouse.simplejs.elements;

public class JsPrivateIdentifier extends JsBaseElement {
    private final String value;
    public JsPrivateIdentifier(String value) {
        this.value = value;
    }
    public String getValue() {
        return value;
    }
}
