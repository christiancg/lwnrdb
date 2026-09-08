package org.techhouse.simplejs.values;

public final class JsString extends JsValue {
    private final String value;

    public JsString(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }
}
