package org.techhouse.simplejs.nodes;

import java.util.List;

public class ObjectExpression extends Expression {
    private final List<JsNode> properties;
    private final boolean trailingComma;

    public ObjectExpression(List<JsNode> properties) {
        this(properties, false);
    }

    public ObjectExpression(List<JsNode> properties, boolean trailingComma) {
        this.properties = properties;
        this.trailingComma = trailingComma;
    }

    public List<JsNode> getProperties() {
        return properties;
    }

    public boolean hasTrailingComma() {
        return trailingComma;
    }
}
