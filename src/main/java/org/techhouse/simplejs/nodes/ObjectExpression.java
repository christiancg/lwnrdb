package org.techhouse.simplejs.nodes;

import java.util.List;

public class ObjectExpression extends Expression {
    private final List<Property> properties;

    public ObjectExpression(List<Property> properties) {
        this.properties = properties;
    }

    public List<Property> getProperties() {
        return properties;
    }
}
