package org.techhouse.simplejs.values;

import org.techhouse.ejson.custom_types.JsonVector;

/**
 * The EJson {@code #vector(v0,...,vn)} custom type as a JavaScript value: a bare data carrier around
 * the component array, shaped like {@link JsTemporalPlainTime} (all behaviour lives in
 * {@code VectorBuiltins}).
 */
public final class JsVector extends JsValue {
    private PropertyTable table;

    private final double[] components;

    public JsVector(double[] components) {
        this.components = components.clone();
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

    public JsonVector toJsonVector() {
        return new JsonVector(components);
    }

    @Override
    public String toString() {
        return toJsonVector().getValue();
    }

    @Override
    public PropertyTable ownProperties() {
        if (table == null) {
            table = new PropertyTable();
        }
        return table;
    }
}
