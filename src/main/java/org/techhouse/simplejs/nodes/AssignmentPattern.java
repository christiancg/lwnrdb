package org.techhouse.simplejs.nodes;

public class AssignmentPattern extends JsNode {
    private final JsNode left;
    private final Expression right;

    public AssignmentPattern(JsNode left, Expression right) {
        this.left = left;
        this.right = right;
    }

    public JsNode getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }
}
