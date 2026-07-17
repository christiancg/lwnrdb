package org.techhouse.simplejs.nodes;

public class VariableDeclarator extends JsNode {
    private final Identifier id;
    private final Expression init;

    public VariableDeclarator(Identifier id, Expression init) {
        this.id = id;
        this.init = init;
    }

    public Identifier getId() {
        return id;
    }

    public Expression getInit() {
        return init;
    }
}
