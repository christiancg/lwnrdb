package org.techhouse.simplejs.nodes;

public class ForOfStatement extends Statement {
    private final JsNode left;
    private final Expression right;
    private final Statement body;
    private final boolean await;

    public ForOfStatement(JsNode left, Expression right, Statement body) {
        this(left, right, body, false);
    }

    public ForOfStatement(JsNode left, Expression right, Statement body, boolean await) {
        this.left = left;
        this.right = right;
        this.body = body;
        this.await = await;
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

    public boolean isAwait() {
        return await;
    }
}
