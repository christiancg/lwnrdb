package org.techhouse.simplejs.elements;

public class JsIdentifier extends JsBaseElement {
    private final String value;
    private final boolean escaped;

    public JsIdentifier(String value) {
        this(value, false);
    }

    public JsIdentifier(String value, boolean escaped) {
        this.value = value;
        this.escaped = escaped;
    }

    public String getValue() {
        return value;
    }

    public boolean isEscaped() {
        return escaped;
    }
}
