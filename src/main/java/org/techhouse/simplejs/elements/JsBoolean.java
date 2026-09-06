package org.techhouse.simplejs.elements;

public class JsBoolean extends JsBaseElement {
    private final boolean value;
    public JsBoolean(boolean value) {
        this.value = value;
    }
    public boolean getValue() {
        return value;
    }
}
