package org.techhouse.simplejs.elements;

public class JsIdentifier extends JsBaseElement {
    private final String value;
    // A name spelled with a unicode escape may not be matched as a contextual keyword.
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
