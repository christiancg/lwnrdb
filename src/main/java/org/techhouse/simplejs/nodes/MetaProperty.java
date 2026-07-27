package org.techhouse.simplejs.nodes;

public class MetaProperty extends Expression {
    private final String meta;
    private final String property;

    public MetaProperty(String meta, String property) {
        this.meta = meta;
        this.property = property;
    }

    public String getMeta() {
        return meta;
    }

    public String getProperty() {
        return property;
    }
}
