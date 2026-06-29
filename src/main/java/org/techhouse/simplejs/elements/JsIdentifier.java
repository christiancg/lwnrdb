package org.techhouse.simplejs.elements;

public class JsIdentifier extends JsBaseElement {
    private final String value;
    public JsIdentifier(String value) {
        this.value = value;
    }
    public String getValue() {
        return value;
    }
}
