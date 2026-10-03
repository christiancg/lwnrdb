package org.techhouse.simplejs.values;

import org.techhouse.ejson.custom_types.JsonVector;

public final class JsVector extends JsValue {
    private PropertyTable table;

    private final double[] components;

    private final JsonVector source;

    public JsVector(double[] components) {
        this(components, null);
    }

    public JsVector(JsonVector source) {
        this(source.getCustomValue(), source);
    }

    private JsVector(double[] components, JsonVector source) {
        this.components = components.clone();
        this.source = source;
    }

    public double[] getComponents() {
        return components.clone();
    }

    public int length() {
        return components.length;
    }

    public double at(int index) {
        return components[index];
    }

    public JsVector copy() {
        return new JsVector(components, source);
    }

    public JsonVector toJsonVector() {
        return source != null ? source : new JsonVector(components);
    }

    @Override
    public String toString() {
        return new JsonVector(components).getValue();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }

    @Override
    public JsValueType getType() {
        return JsValueType.VECTOR;
    }
}
