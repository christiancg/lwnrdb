package org.techhouse.simplejs.nodes;

import java.util.List;

public class ArrayPattern extends JsNode {
    private final List<JsNode> elements;

    public ArrayPattern(List<JsNode> elements) {
        this.elements = elements;
    }

    public List<JsNode> getElements() {
        return elements;
    }
}
