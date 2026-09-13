package org.techhouse.simplejs.nodes;

public class RestElement extends JsNode {
    private final JsNode argument;

    public RestElement(JsNode argument) {
        this.argument = argument;
    }

    public JsNode getArgument() {
        return argument;
    }

    @Override
    public NodeType getType() {
        return NodeType.REST_ELEMENT;
    }
}
