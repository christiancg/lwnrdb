package org.techhouse.simplejs.nodes;

public class BinaryExpression extends Expression {
    private final String operator;
    private final BinaryOperator kind;
    private final Expression left;
    private final Expression right;

    public BinaryExpression(String operator, Expression left, Expression right) {
        this.operator = operator;
        this.kind = BinaryOperator.of(operator);
        this.left = left;
        this.right = right;
    }

    public String getOperator() {
        return operator;
    }

    public BinaryOperator kind() {
        return kind;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    @Override
    public NodeType getType() {
        return NodeType.BINARY_EXPRESSION;
    }
}
