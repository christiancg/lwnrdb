package org.techhouse.simplejs.nodes;

import java.util.List;

public class ObjectExpression extends Expression {
    private final List<JsNode> properties;

    public ObjectExpression(List<JsNode> properties) {
        this.properties = properties;
    }

    public List<JsNode> getProperties() {
        return properties;
    }
}
