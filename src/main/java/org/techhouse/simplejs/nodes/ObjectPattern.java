package org.techhouse.simplejs.nodes;

import java.util.List;

public class ObjectPattern extends JsNode {
    private final List<JsNode> properties;

    public ObjectPattern(List<JsNode> properties) {
        this.properties = properties;
    }

    public List<JsNode> getProperties() {
        return properties;
    }
}
