package org.techhouse.simplejs.elements;

public class JsSeparator extends JsBaseElement {
    private final Character value;
    public JsSeparator(Character value) {
        this.value = value;
    }
    public Character getValue() {
        return value;
    }
}
