package org.techhouse.simplejs.values;

public final class JsNumber extends JsValue {
    private final double value;

    public JsNumber(double value) {
        this.value = value;
    }

    public double getValue() {
        return value;
    }
}
