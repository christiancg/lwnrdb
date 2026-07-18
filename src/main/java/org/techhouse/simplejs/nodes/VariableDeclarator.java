package org.techhouse.simplejs.nodes;

public class VariableDeclarator extends JsNode {
    private final JsNode id;
    private final Expression init;

    public VariableDeclarator(JsNode id, Expression init) {
        this.id = id;
        this.init = init;
    }

    public JsNode getId() {
        return id;
    }

    public Expression getInit() {
        return init;
    }
}
