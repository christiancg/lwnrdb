package org.techhouse.simplejs.nodes;

public class ForInStatement extends Statement {
    private final JsNode left;
    private final Expression right;
    private final Statement body;

    public ForInStatement(JsNode left, Expression right, Statement body) {
        this.left = left;
        this.right = right;
        this.body = body;
    }

    public JsNode getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    public Statement getBody() {
        return body;
    }
}
